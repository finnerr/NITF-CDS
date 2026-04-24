// Portable Xerces-C XML Schema validator.
// Uses only Xerces-C 3.x core APIs (SAX2) — works on 3.2 and 3.3+.
//
// Usage: validate <schema.xsd> <doc.xml>
// Exit:  0 valid, 1 invalid, 2 usage/setup error

#include <cstdio>
#include <cstdlib>
#include <iostream>
#include <string>
#include <limits.h>

#include <xercesc/util/PlatformUtils.hpp>
#include <xercesc/util/XMLString.hpp>
#include <xercesc/sax2/SAX2XMLReader.hpp>
#include <xercesc/sax2/XMLReaderFactory.hpp>
#include <xercesc/sax2/DefaultHandler.hpp>
#include <xercesc/sax/SAXParseException.hpp>
#include <xercesc/sax/SAXException.hpp>

using namespace xercesc;

// RAII transcoder: XMLCh* ↔ char*
struct X {
    XMLCh* ws;
    X(const char* s) : ws(XMLString::transcode(s)) {}
    ~X() { XMLString::release(&ws); }
    operator const XMLCh*() const { return ws; }
};

struct S {
    char* s;
    S(const XMLCh* w) : s(XMLString::transcode(w)) {}
    ~S() { XMLString::release(&s); }
    operator const char*() const { return s; }
};

class CountingHandler : public DefaultHandler {
public:
    int warnings = 0;
    int errors   = 0;
    int fatals   = 0;

    void warning(const SAXParseException& e) override {
        warnings++;
        report("warning", e);
    }
    void error(const SAXParseException& e) override {
        errors++;
        report("error", e);
    }
    void fatalError(const SAXParseException& e) override {
        fatals++;
        report("fatal", e);
    }

private:
    void report(const char* level, const SAXParseException& e) {
        S sys(e.getSystemId() ? e.getSystemId() : X(""));
        S msg(e.getMessage());
        std::fprintf(stderr, "[%s] %s:%llu:%llu: %s\n",
                     level,
                     (const char*)sys,
                     (unsigned long long)e.getLineNumber(),
                     (unsigned long long)e.getColumnNumber(),
                     (const char*)msg);
    }
};

int main(int argc, char** argv) {
    if (argc != 3) {
        std::fprintf(stderr, "usage: %s <schema.xsd> <doc.xml>\n", argv[0]);
        return 2;
    }
    const char* xmlPath = argv[2];

    // Xerces resolves the schemaLocation URI against the XML document's
    // base URI, so a relative schema path would be looked up alongside the
    // XML file. Convert to an absolute path (or pass through if already
    // absolute or already a URI) so resolution is unambiguous.
    std::string schemaPathStr;
    {
        std::string arg1 = argv[1];
        if (arg1.rfind("file://", 0) == 0 || (!arg1.empty() && arg1[0] == '/')) {
            schemaPathStr = arg1;
        } else {
            char resolved[PATH_MAX];
            if (realpath(arg1.c_str(), resolved)) {
                schemaPathStr = resolved;
            } else {
                std::fprintf(stderr, "cannot resolve schema path '%s'\n", arg1.c_str());
                return 2;
            }
        }
    }
    const char* schemaPath = schemaPathStr.c_str();

    try {
        XMLPlatformUtils::Initialize();
    } catch (const XMLException& e) {
        S msg(e.getMessage());
        std::fprintf(stderr, "xerces init failed: %s\n", (const char*)msg);
        return 2;
    }

    int rc = 0;
    SAX2XMLReader* reader = nullptr;
    try {
        reader = XMLReaderFactory::createXMLReader();

        // Apply each feature independently; unknown/unsupported features
        // are logged and skipped so the tool stays portable across
        // Xerces-C 3.x builds.
        auto trySetFeature = [&](const char* id, bool v) {
            try {
                reader->setFeature(X(id), v);
            } catch (const SAXNotRecognizedException&) {
                std::fprintf(stderr, "[info] feature not recognized: %s\n", id);
            } catch (const SAXNotSupportedException&) {
                std::fprintf(stderr, "[info] feature not supported: %s\n", id);
            }
        };
        trySetFeature("http://xml.org/sax/features/namespaces",                          true);
        trySetFeature("http://xml.org/sax/features/validation",                          true);
        trySetFeature("http://apache.org/xml/features/validation/schema",                true);
        trySetFeature("http://apache.org/xml/features/validation/schema-full-checking", true);
        trySetFeature("http://apache.org/xml/features/validation/dynamic",               false);
        trySetFeature("http://apache.org/xml/features/disallow-doctype-decl",            true);
        trySetFeature("http://xml.org/sax/features/external-general-entities",           false);
        trySetFeature("http://xml.org/sax/features/external-parameter-entities",         false);

        // Pin the schema location so validation does not depend on any
        // xsi:noNamespaceSchemaLocation / xsi:schemaLocation hints in the
        // document itself. For a targetNamespace'd schema we'd use the
        // "external-schemaLocation" property; for no-namespace (lean)
        // envelopes the "noNamespaceSchemaLocation" property is correct.
        // Try namespaced form first, fall back to no-namespace.
        // Determine at runtime by grepping schema for targetNamespace.
        FILE* fp = std::fopen(schemaPath, "rb");
        std::string head;
        if (fp) {
            char buf[4096];
            size_t n = std::fread(buf, 1, sizeof(buf), fp);
            head.assign(buf, n);
            std::fclose(fp);
        }
        auto pos = head.find("targetNamespace");
        if (pos != std::string::npos) {
            // Extract "ns-uri" after targetNamespace="
            auto q1 = head.find('"', pos);
            auto q2 = (q1 == std::string::npos) ? std::string::npos : head.find('"', q1 + 1);
            if (q1 != std::string::npos && q2 != std::string::npos) {
                std::string ns  = head.substr(q1 + 1, q2 - q1 - 1);
                std::string pair = ns + " " + schemaPath;
                reader->setProperty(
                    X("http://apache.org/xml/properties/schema/external-schemaLocation"),
                    (void*)(const XMLCh*)X(pair.c_str()));
            }
        } else {
            reader->setProperty(
                X("http://apache.org/xml/properties/schema/external-noNamespaceSchemaLocation"),
                (void*)(const XMLCh*)X(schemaPath));
        }

        CountingHandler handler;
        reader->setContentHandler(&handler);
        reader->setErrorHandler(&handler);

        try {
            reader->parse(xmlPath);
        } catch (const XMLException& e) {
            S msg(e.getMessage());
            std::fprintf(stderr, "[xml-exception] %s\n", (const char*)msg);
            rc = 1;
        } catch (const SAXParseException&) {
            // Counted via handler already.
            rc = 1;
        } catch (...) {
            std::fprintf(stderr, "[unknown-exception]\n");
            rc = 1;
        }

        std::fprintf(stderr, "summary: warnings=%d errors=%d fatals=%d\n",
                     handler.warnings, handler.errors, handler.fatals);

        if (handler.errors > 0 || handler.fatals > 0) rc = 1;
        if (rc == 0) {
            std::printf("%s validates against %s\n", xmlPath, schemaPath);
        }
    } catch (const SAXNotRecognizedException& e) {
        S msg(e.getMessage());
        std::fprintf(stderr, "[setup-error] not recognized: %s\n", (const char*)msg);
        rc = 2;
    } catch (const SAXNotSupportedException& e) {
        S msg(e.getMessage());
        std::fprintf(stderr, "[setup-error] not supported: %s\n", (const char*)msg);
        rc = 2;
    } catch (const XMLException& e) {
        S msg(e.getMessage());
        std::fprintf(stderr, "[setup-error] xml: %s\n", (const char*)msg);
        rc = 2;
    } catch (const std::exception& e) {
        std::fprintf(stderr, "[setup-error] %s\n", e.what());
        rc = 2;
    } catch (...) {
        std::fprintf(stderr, "[setup-error] unknown\n");
        rc = 2;
    }

    delete reader;
    XMLPlatformUtils::Terminate();
    return rc;
}
