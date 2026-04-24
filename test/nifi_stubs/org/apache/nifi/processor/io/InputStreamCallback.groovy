package org.apache.nifi.processor.io

interface InputStreamCallback {
    void process(InputStream input)
}
