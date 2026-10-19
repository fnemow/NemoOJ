ARG BASE_IMAGE
FROM ${BASE_IMAGE}
USER root
COPY json-lite.hpp /usr/local/include/nemo/json-lite.hpp
COPY json-lite.cpp /tmp/json-lite.cpp
RUN mkdir -p /opt/nemooj && g++ -std=c++17 -O2 -c /tmp/json-lite.cpp -o /opt/nemooj/json-lite.o \
    && ar rcs /opt/nemooj/libjson-lite.a /opt/nemooj/json-lite.o \
    && chmod a+r /opt/nemooj/libjson-lite.a /usr/local/include/nemo/json-lite.hpp
USER nobody
