// Batch runner: send -> recv round trip on every .ntf/.nitf file in a
// directory. Produces a pass/fail summary matching the Python corpus test.
//
// Usage:
//   groovy -cp "<daffodil lib>:test/nifi_stubs" test/batch_roundtrip.groovy <dir> <schema.xsd>

import org.apache.nifi.processor.io.InputStreamCallback
import org.apache.nifi.processor.io.OutputStreamCallback

class MockFlowFile { byte[] content = new byte[0]; Map<String,String> attrs = [:] }
class MockSession {
    MockFlowFile current
    MockSession(MockFlowFile ff) { this.current = ff }
    MockFlowFile get() { def r = current; current = null; return r }
    void read(MockFlowFile ff, InputStreamCallback cb) { cb.process(new ByteArrayInputStream(ff.content)) }
    MockFlowFile write(MockFlowFile ff, OutputStreamCallback cb) {
        def baos = new ByteArrayOutputStream(); cb.process(baos); ff.content = baos.toByteArray(); return ff
    }
    MockFlowFile putAttribute(MockFlowFile ff, String k, String v) { ff.attrs[k] = v; return ff }
    void transfer(MockFlowFile ff, String rel) { current = ff; lastRel = rel }
    String lastRel = null
}
class MockPropertyValue {
    String v; MockPropertyValue(String v) { this.v = v }
    MockPropertyValue evaluateAttributeExpressions(ff) { this }
    String getValue() { v }
}
class MockContext {
    Map<String,String> props
    MockContext(Map<String,String> props) { this.props = props }
    MockPropertyValue getProperty(String n) { new MockPropertyValue(props[n]) }
}
class MockLog {
    List<String> errors = []
    void info (msg)             {}
    void info (msg, Throwable t){}
    void warn (msg)             {}
    void warn (msg, Throwable t){}
    void error(msg)             { errors << msg.toString() }
    void error(msg, Throwable t){ errors << msg.toString() + ": " + t.message }
    void debug(msg)             {}
}

if (args.length < 2) {
    println "usage: groovy batch_roundtrip.groovy <dir> <nitf.dfdl.xsd>"
    System.exit(2)
}
def dir = new File(args[0])
def schemaPath = new File(args[1]).absolutePath
def files = dir.listFiles()?.findAll { it.name.toLowerCase() ==~ /.*\.n(i)?tf$/ }?.sort { it.name }
assert files, "no .ntf/.nitf files in ${dir}"

def sendScript = new File('nitf_send.groovy').absolutePath
def recvScript = new File('nitf_recv.groovy').absolutePath
def sysTmp = new File(System.getProperty("java.io.tmpdir"))

def cleanSidecars = {
    sysTmp.listFiles()?.findAll { it.name.startsWith("daffodil-") && it.name.endsWith(".blob") }?.each { it.delete() }
}

def results = []
files.eachWithIndex { f, i ->
    def name = f.name
    print String.format("[%2d/%2d] %-20s ", i+1, files.size(), name)
    def log = new MockLog()
    def status = "PASS"; def detail = ""
    try {
        // SEND
        def sSess = new MockSession(new MockFlowFile(content: f.bytes))
        def sCtx  = new MockContext(['Schema Path': schemaPath, 'Max Binary Size': '0'])
        new GroovyShell(this.class.classLoader, new Binding([session: sSess, context: sCtx, log: log,
            REL_SUCCESS: 'success', REL_FAILURE: 'failure'])).evaluate(new File(sendScript))
        if (sSess.lastRel != 'success') { status = "SEND_FAIL"; detail = log.errors.join(' | '); throw new RuntimeException(detail) }
        def envelope = sSess.current

        cleanSidecars()

        // RECV
        def rSess = new MockSession(new MockFlowFile(content: envelope.content))
        def rCtx  = new MockContext(['Schema Path': schemaPath])
        new GroovyShell(this.class.classLoader, new Binding([session: rSess, context: rCtx, log: log,
            REL_SUCCESS: 'success', REL_FAILURE: 'failure'])).evaluate(new File(recvScript))
        if (rSess.lastRel != 'success') { status = "RECV_FAIL"; detail = log.errors.join(' | '); throw new RuntimeException(detail) }
        def out = rSess.current

        // CMP
        byte[] orig = f.bytes; byte[] recon = out.content
        if (orig.length == recon.length && Arrays.equals(orig, recon)) {
            status = "PASS"
            detail = "${orig.length} B, blobs=${envelope.attrs['nitf.blob.count']}"
        } else {
            status = "DIFF"
            detail = "orig=${orig.length} recon=${recon.length}"
        }
    } catch (Throwable t) {
        if (status == "PASS") status = "ERROR"
        detail = detail ?: t.message
    } finally {
        cleanSidecars()
    }
    results << [name: name, status: status, detail: detail]
    println "${status}  ${detail}"
}

println "\n========== SUMMARY =========="
def byStatus = results.groupBy { it.status }
byStatus.each { k, v -> println "  ${k}: ${v.size()}" }
def fails = results.findAll { it.status != 'PASS' }
if (fails) {
    println "\nFailures:"
    fails.each { println "  ${it.name}: ${it.status} — ${it.detail}" }
}
System.exit(fails.isEmpty() ? 0 : 1)
