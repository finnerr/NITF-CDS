// Local harness for nitf_send.groovy + nitf_recv.groovy.
// Stubs the NiFi bindings (session / context / log / REL_SUCCESS /
// REL_FAILURE) and drives a full send -> recv round trip against a
// .ntf on disk, then cmp's the result.
//
// Usage:
//   groovy -cp "<daffodil lib>:test/nifi_stubs" test/run_roundtrip.groovy <input.ntf> <schema.xsd>

import org.apache.nifi.processor.io.InputStreamCallback
import org.apache.nifi.processor.io.OutputStreamCallback

// ---- mock FlowFile: just holds bytes + attributes ----
class MockFlowFile {
    byte[] content = new byte[0]
    Map<String,String> attrs = [:]
}

// ---- mock ProcessSession ----
class MockSession {
    MockFlowFile current
    MockSession(MockFlowFile ff) { this.current = ff }
    MockFlowFile get() { def r = current; current = null; return r }
    void read(MockFlowFile ff, InputStreamCallback cb) {
        cb.process(new ByteArrayInputStream(ff.content))
    }
    MockFlowFile write(MockFlowFile ff, OutputStreamCallback cb) {
        def baos = new ByteArrayOutputStream()
        cb.process(baos)
        ff.content = baos.toByteArray()
        return ff
    }
    MockFlowFile putAttribute(MockFlowFile ff, String k, String v) {
        ff.attrs[k] = v; return ff
    }
    void transfer(MockFlowFile ff, String rel) {
        println "[session] transferred to ${rel} (${ff.content.length} bytes, attrs=${ff.attrs})"
        current = ff // let caller grab it back
    }
}

// ---- mock PropertyValue / Context ----
class MockPropertyValue {
    String v
    MockPropertyValue(String v) { this.v = v }
    MockPropertyValue evaluateAttributeExpressions(ff) { this }
    String getValue() { v }
}
class MockContext {
    Map<String,String> props
    MockContext(Map<String,String> props) { this.props = props }
    MockPropertyValue getProperty(String name) { new MockPropertyValue(props[name]) }
}

// ---- mock log ----
class MockLog {
    void info (msg)            { println "[INFO ] ${msg}" }
    void info (msg, Throwable t){ println "[INFO ] ${msg}"; t.printStackTrace() }
    void warn (msg)            { println "[WARN ] ${msg}" }
    void warn (msg, Throwable t){ println "[WARN ] ${msg}"; t.printStackTrace() }
    void error(msg)            { println "[ERROR] ${msg}" }
    void error(msg, Throwable t){ println "[ERROR] ${msg}"; t.printStackTrace() }
    void debug(msg)            { /* quiet */ }
}

// ---- entry ----
if (args.length < 2) {
    println "usage: groovy run_roundtrip.groovy <input.ntf> <nitf.dfdl.xsd>"
    System.exit(2)
}
def inputNtf = new File(args[0])
def schemaPath = new File(args[1]).absolutePath
assert inputNtf.exists(), "no such file: ${inputNtf}"
def log = new MockLog()

// ---- SEND ----
println "=== SEND ==="
def sendIn = new MockFlowFile(content: inputNtf.bytes)
def sendSession = new MockSession(sendIn)
def sendContext = new MockContext(['Schema Path': schemaPath, 'Max Binary Size': '0'])
def sendBinding = new Binding([
    session: sendSession, context: sendContext, log: log,
    REL_SUCCESS: 'success', REL_FAILURE: 'failure',
])
def sendScript = new File('nitf_send.groovy').absolutePath
new GroovyShell(this.class.classLoader, sendBinding).evaluate(new File(sendScript))
def envelope = sendSession.current
assert envelope != null && envelope.content.length > 0, "send produced no output"
def envFile = new File("/tmp/nitf_test.envelope.xml")
envFile.bytes = envelope.content
println "wrote envelope: ${envFile} (${envelope.content.length} bytes)"

// Honest test: nuke any Daffodil-written sidecar blobs in java.io.tmpdir
// so recv MUST rely on the base64 blobs in the envelope, not residual
// files from the send pass.
def sysTmp = new File(System.getProperty("java.io.tmpdir"))
sysTmp.listFiles()?.findAll { it.name.startsWith("daffodil-") && it.name.endsWith(".blob") }?.each {
    println "[test] removing stale sidecar: ${it}"
    it.delete()
}

// ---- RECV ----
println "=== RECV ==="
def recvIn = new MockFlowFile(content: envelope.content)
def recvSession = new MockSession(recvIn)
def recvContext = new MockContext(['Schema Path': schemaPath])
def recvBinding = new Binding([
    session: recvSession, context: recvContext, log: log,
    REL_SUCCESS: 'success', REL_FAILURE: 'failure',
])
def recvScript = new File('nitf_recv.groovy').absolutePath
new GroovyShell(this.class.classLoader, recvBinding).evaluate(new File(recvScript))
def out = recvSession.current
assert out != null && out.content.length > 0, "recv produced no output"
def outFile = new File("/tmp/nitf_test.roundtrip.ntf")
outFile.bytes = out.content
println "wrote reconstructed NITF: ${outFile} (${out.content.length} bytes)"

// ---- CMP ----
def orig = inputNtf.bytes
def recon = out.content
if (orig.length == recon.length && Arrays.equals(orig, recon)) {
    println "=== PASS === byte-identical round trip (${orig.length} bytes)"
    System.exit(0)
} else {
    println "=== FAIL === original=${orig.length} reconstructed=${recon.length}"
    int diff = 0
    def min = Math.min(orig.length, recon.length)
    for (int i = 0; i < min; i++) if (orig[i] != recon[i]) { diff = i; break }
    println "first diff at byte ${diff}"
    System.exit(1)
}
