# Portable build for the Xerces-C schema validator.
# Tries pkg-config first; falls back to a system-wide / conda-prefix probe.

XERCES_CFLAGS := $(shell pkg-config --cflags xerces-c 2>/dev/null)
XERCES_LIBS   := $(shell pkg-config --libs   xerces-c 2>/dev/null)

# Fallbacks if pkg-config didn't find anything.
ifeq ($(strip $(XERCES_LIBS)),)
  ifneq ($(wildcard $(CONDA_PREFIX)/include/xercesc/util/PlatformUtils.hpp),)
    XERCES_CFLAGS := -I$(CONDA_PREFIX)/include
    XERCES_LIBS   := -L$(CONDA_PREFIX)/lib -lxerces-c -Wl,-rpath,$(CONDA_PREFIX)/lib
  else ifneq ($(wildcard /usr/include/xercesc/util/PlatformUtils.hpp),)
    XERCES_CFLAGS :=
    XERCES_LIBS   := -lxerces-c
  else
    $(error Could not find xerces-c headers. Install xerces-c-devel (dnf) or xerces-c (conda-forge).)
  endif
endif

CXX      ?= g++
CXXFLAGS ?= -O2 -std=c++14 -Wall -Wextra
TARGET   := validate
SRC      := validate.cpp

$(TARGET): $(SRC)
	$(CXX) $(CXXFLAGS) $(XERCES_CFLAGS) $< -o $@ $(XERCES_LIBS)

.PHONY: clean info test test-all samples
clean:
	rm -f $(TARGET)

info:
	@echo "CXX          = $(CXX)"
	@echo "XERCES_CFLAGS= $(XERCES_CFLAGS)"
	@echo "XERCES_LIBS  = $(XERCES_LIBS)"

# Regenerate inline samples from the archived base64 envelopes.
samples:
	python3 tools/gen_inline.py

# Validate one representative sample against the schema pair.
test: $(TARGET)
	./$(TARGET) nitf_envelope_lean.xsd samples/i_3114e_inline.xml

# Run xmllint + xerces-c against every inline sample and report
# pass/fail for each. Prints details for the first failing file.
test-all: $(TARGET)
	./tools/validate_all.sh
