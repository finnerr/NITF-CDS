package org.apache.nifi.processor.io

interface OutputStreamCallback {
    void process(OutputStream output)
}
