// Copyright (c) 2026 Tyler Finn.
// Part of the NITF-over-CDS project.
//
// NiFi ExecuteGroovyScript processor: wraps a .ntf FlowFile in a
// <NitfMessage> envelope (urn:cds:nitf:1). Calls Daffodil's JAPI in
// process — no subprocess, no CLI.
//
// Processor properties expected:
//   Schema Path      -> absolute path to nitf.dfdl.xsd on the NiFi node
//   Max Binary Size  -> integer, forwarded as external var
//                       {urn:nitf:2.1}maxBinarySizeInBytes. "0" forces
//                       every payload into BLOB mode (recommended).
//
// Additional classpath on the processor must include the Daffodil JARs
// (daffodil-japi, daffodil-runtime1, daffodil-io, daffodil-lib, Scala
// runtime, ICU, Xerces shims, etc.).

import groovy.transform.Field
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

import org.apache.daffodil.api.Daffodil
import org.apache.daffodil.api.DataProcessor

@Field static final AtomicReference<DataProcessor> DP_REF = new AtomicReference<>()
@Field static final Object COMPILE_LOCK = new Object()

def getProcessor(String schemaPath) {
    def dp = DP_REF.get()
    if (dp != null) return dp
    synchronized (COMPILE_LOCK) {
        dp = DP_REF.get()
        if (dp != null) return dp
        def compiler = Daffodil.compiler()
        def pf = compiler.compileFile(new File(schemaPath))
        if (pf.isError()) {
            pf.getDiagnostics().each { log.error("DFDL compile: " + it.toString()) }
            throw new RuntimeException("DFDL schema compile failed")
        }
        dp = pf.onPath("/")
        if (dp.isError()) {
            dp.getDiagnostics().each { log.error("DataProcessor: " + it.toString()) }
            throw new RuntimeException("DataProcessor creation failed")
        }
        DP_REF.set(dp)
        return dp
    }
}

def sha256Hex(byte[] bytes) {
    def md = MessageDigest.getInstance("SHA-256")
    md.digest(bytes).collect { String.format("%02x", it) }.join()
}

def xmlEscape(String s) {
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
     .replace("\"", "&quot;").replace("'", "&apos;")
}

// ---- entrypoint ----
def flowFile = session.get()
if (flowFile == null) return

def schemaPath   = context.getProperty('Schema Path').evaluateAttributeExpressions(flowFile).getValue()
def maxBinSize   = (context.getProperty('Max Binary Size').evaluateAttributeExpressions(flowFile).getValue() ?: '0')

Path blobDir = Files.createTempDirectory("nitf-blobs-")
try {
    def dp = getProcessor(schemaPath)
    def extVars = new HashMap<String, String>()
    extVars.put("{urn:nitf:2.1}maxBinarySizeInBytes", maxBinSize.toString())
    dp = dp.withExternalVariables(extVars)

    // Daffodil writes BlobPayload external files into the process CWD by
    // default. Point it at our tempdir for this invocation.
    def prevUserDir = System.getProperty("user.dir")
    System.setProperty("user.dir", blobDir.toString())

    def xmlBuf = new ByteArrayOutputStream()
    def outputter = Daffodil.newXMLTextInfosetOutputter(xmlBuf, /*pretty=*/ false)

    try {
        session.read(flowFile, { InputStream input ->
            def isd = Daffodil.newInputSourceDataInputStream(input)
            def pr = dp.parse(isd, outputter)
            if (pr.isError()) {
                pr.getDiagnostics().each { log.error("Parse: " + it.toString()) }
                throw new RuntimeException("Daffodil parse failed")
            }
        } as org.apache.nifi.processor.io.InputStreamCallback)
    } finally {
        if (prevUserDir) System.setProperty("user.dir", prevUserDir)
    }

    def rawXml = xmlBuf.toString("UTF-8")
    // strip any xml declaration Daffodil emits — we'll write our own
    rawXml = rawXml.replaceFirst(/^\s*<\?xml[^?]*\?>\s*/, '')

    // Walk <BlobPayload uri="file:/...">...</BlobPayload> elements:
    // hash the referenced file, rewrite to uri="cds:blob:N", collect
    // manifest entries + base64 bodies.
    // Daffodil emits: <BlobPayload>file:///path/to/blob</BlobPayload>
    def blobRe = ~/<BlobPayload>([^<]+)<\/BlobPayload>/
    def manifestEntries = []
    def blobBodies = []
    int idx = 0
    def rewritten = rawXml.replaceAll(blobRe) { full, uriStr ->
        def uri = java.net.URI.create(uriStr.trim())
        def blobFile = new File(uri)
        byte[] bytes = blobFile.bytes
        def digest = sha256Hex(bytes)
        manifestEntries << [index: idx, size: bytes.length, sha256: digest]
        blobBodies << [index: idx, b64: Base64.encoder.encodeToString(bytes)]
        def rebuilt = "<BlobPayload>cds:blob:${idx}</BlobPayload>"
        idx++
        rebuilt
    }

    // ---- emit envelope ----
    def out = new StringBuilder(rewritten.length() + 4096)
    out << '<?xml version="1.0" encoding="UTF-8"?>\n'
    out << '<NitfMessage xmlns="urn:cds:nitf:1" version="1">\n'
    out << '  <Manifest>\n'
    manifestEntries.each { e ->
        out << "    <Blob index=\"${e.index}\" size=\"${e.size}\" sha256=\"${e.sha256}\"/>\n"
    }
    out << '  </Manifest>\n'
    if (!blobBodies.isEmpty()) {
        out << '  <Blobs>\n'
        blobBodies.each { b ->
            out << "    <BlobData index=\"${b.index}\">${b.b64}</BlobData>\n"
        }
        out << '  </Blobs>\n'
    }
    out << '  <Payload>\n'
    out << rewritten
    out << '\n  </Payload>\n'
    out << '</NitfMessage>\n'

    flowFile = session.write(flowFile, { OutputStream os ->
        os.write(out.toString().getBytes("UTF-8"))
    } as org.apache.nifi.processor.io.OutputStreamCallback)

    flowFile = session.putAttribute(flowFile, 'mime.type', 'application/xml')
    flowFile = session.putAttribute(flowFile, 'nitf.blob.count', manifestEntries.size().toString())
    session.transfer(flowFile, REL_SUCCESS)

} catch (Throwable t) {
    log.error("nitf_send: " + t.message, t)
    session.transfer(flowFile, REL_FAILURE)
} finally {
    // best-effort cleanup of blob sidecars
    try { blobDir.toFile().eachFile { it.delete() }; blobDir.toFile().delete() } catch (ignored) {}
}
