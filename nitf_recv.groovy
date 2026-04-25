// Copyright (c) 2026 Tyler Finn.
// Part of the NITF-over-CDS project.
//
// NiFi ExecuteGroovyScript processor: takes a <NitfMessage> envelope
// FlowFile, verifies per-blob SHA-256, restores blob sidecars on disk,
// rewrites cds:blob:N URIs to file://... and calls Daffodil unparse to
// emit the reconstructed .ntf as the output FlowFile.
//
// Processor properties expected:
//   Schema Path   -> absolute path to nitf.dfdl.xsd on the NiFi node
//
// Additional classpath must include the Daffodil JARs (see send script).

import groovy.transform.Field
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import org.apache.daffodil.api.Daffodil
import org.apache.daffodil.api.DataProcessor

@Field static final AtomicReference<DataProcessor> DP_REF = new AtomicReference<>()
@Field static final Object COMPILE_LOCK = new Object()
// Counts threads currently inside unparse. Must stay ≤ 1 (same DataProcessor
// instance is reused; Daffodil unparse is not thread-safe for concurrent calls).
@Field static final AtomicInteger ACTIVE_UNPARSES = new AtomicInteger(0)

def getProcessor(String schemaPath) {
    def dp = DP_REF.get()
    if (dp != null) return dp
    synchronized (COMPILE_LOCK) {
        dp = DP_REF.get()
        if (dp != null) return dp
        Thread.currentThread().setContextClassLoader(Daffodil.class.classLoader)
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

def deleteDirRecursive(File dir) {
    dir.eachFileRecurse { it.delete() }
    dir.delete()
}

// ---- entrypoint ----
def flowFile = session.get()
if (flowFile == null) return

def schemaPath = context.getProperty('Schema Path').evaluateAttributeExpressions(flowFile).getValue()
if (!schemaPath) {
    log.error("nitf_recv: 'Schema Path' processor property is not set")
    session.transfer(flowFile, REL_FAILURE)
    return
}

// MEMORY NOTE: the entire XML envelope (including base64-encoded blobs) is
// loaded into a single String. Peak heap usage is ~3× the raw NITF size.
// Set NiFi JVM heap in bootstrap.conf to accommodate the largest expected
// file × 3. A streaming StAX rewrite is required for multi-GB files.
Path blobDir = Files.createTempDirectory("nitf-recv-blobs-")
if (ACTIVE_UNPARSES.incrementAndGet() > 1) {
    ACTIVE_UNPARSES.decrementAndGet()
    throw new RuntimeException(
        "nitf_recv: concurrent unparse attempted — Concurrent Tasks must be 1")
}
try {
    // Read the entire envelope into memory.
    String envelope = null
    session.read(flowFile, { InputStream input ->
        envelope = input.getText("UTF-8")
    } as org.apache.nifi.processor.io.InputStreamCallback)

    // ---- extract manifest ----
    def manifestRe = ~/<Blob\s+([^\/>]*?)\/>/
    def attrRe     = ~/(\w+)="([^"]*)"/
    Map<Integer, Map> manifest = [:]
    def manifestBlock = (envelope =~ /(?s)<Manifest>(.*?)<\/Manifest>/)
    if (manifestBlock.find()) {
        manifestBlock.group(1).eachMatch(manifestRe) { match, attrStr ->
            def attrs = [:]
            attrStr.eachMatch(attrRe) { _, k, v -> attrs[k] = v }
            if (attrs.index != null) {
                manifest[attrs.index as Integer] = [size: attrs.size as Long, sha256: attrs.sha256]
            }
        }
    }

    // ---- extract blob bodies, verify hashes, spill to disk ----
    def blobsBlock = (envelope =~ /(?s)<Blobs>(.*?)<\/Blobs>/)
    Map<Integer, File> blobFiles = [:]
    if (blobsBlock.find()) {
        def blobDataRe = ~/(?s)<BlobData\s+index="(\d+)"[^>]*>(.*?)<\/BlobData>/
        blobsBlock.group(1).eachMatch(blobDataRe) { match, idxStr, body ->
            int idx = idxStr as int
            byte[] bytes = Base64.decoder.decode(body.replaceAll(/\s+/, ''))
            def expected = manifest[idx]?.sha256
            if (expected == null) {
                throw new RuntimeException("Blob ${idx} present in <Blobs> but missing from <Manifest>")
            }
            def actual = sha256Hex(bytes)
            if (expected != actual) {
                throw new RuntimeException("Hash mismatch on blob ${idx}: expected ${expected}, got ${actual}")
            }
            def bf = new File(blobDir.toFile(), "blob-${idx}.bin")
            bf.bytes = bytes
            blobFiles[idx] = bf
        }
    }
    if (blobFiles.size() != manifest.size()) {
        throw new RuntimeException(
            "Blob count mismatch: manifest declares ${manifest.size()} blobs, envelope contains ${blobFiles.size()}")
    }

    // ---- extract payload subtree ----
    // Payload is inline DFDL infoset XML. Send side inserts xmlns=""
    // on the NITF root so children stay unqualified when inlined
    // inside the envelope; Daffodil's unparser accepts that as-is.
    def payloadMatcher = (envelope =~ /(?s)<Payload>\s*(.*?)\s*<\/Payload>/)
    if (!payloadMatcher.find()) {
        throw new RuntimeException("Envelope has no <Payload>")
    }
    String payloadXml = payloadMatcher.group(1)

    // ---- rewrite <BlobPayload>cds:blob:N</BlobPayload> -> file:// URI ----
    payloadXml = payloadXml.replaceAll(/<BlobPayload>cds:blob:(\d+)<\/BlobPayload>/) { full, idxStr ->
        int idx = idxStr as int
        def bf = blobFiles[idx]
        if (!bf) throw new RuntimeException("Payload references cds:blob:${idx} but no such blob in envelope")
        "<BlobPayload>${bf.toURI()}</BlobPayload>"
    }

    // ---- unparse ----
    def dp = getProcessor(schemaPath)
    flowFile = session.write(flowFile, { OutputStream os ->
        def inputter = Daffodil.newXMLTextInfosetInputter(new ByteArrayInputStream(payloadXml.getBytes("UTF-8")))
        def channel = java.nio.channels.Channels.newChannel(os)
        def ur = dp.unparse(inputter, channel)
        if (ur.isError()) {
            ur.getDiagnostics().each { log.error("Unparse: " + it.toString()) }
            throw new RuntimeException("Daffodil unparse failed")
        }
    } as org.apache.nifi.processor.io.OutputStreamCallback)

    flowFile = session.putAttribute(flowFile, 'mime.type', 'application/octet-stream')
    flowFile = session.putAttribute(flowFile, 'nitf.blob.count', manifest.size().toString())
    session.transfer(flowFile, REL_SUCCESS)

} catch (Throwable t) {
    log.error("nitf_recv: " + t.message, t)
    session.transfer(flowFile, REL_FAILURE)
} finally {
    ACTIVE_UNPARSES.decrementAndGet()
    try { deleteDirRecursive(blobDir.toFile()) } catch (Throwable t) {
        log.warn("nitf_recv: failed to clean up blob tempdir ${blobDir}: ${t.message}")
    }
}
