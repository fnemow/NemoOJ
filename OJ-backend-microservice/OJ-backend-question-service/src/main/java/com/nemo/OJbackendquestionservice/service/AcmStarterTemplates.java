package com.nemo.OJbackendquestionservice.service;

import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Browser starter code only; it is never used as a judge reference solution. */
final class AcmStarterTemplates {
    private static final Map<String,String> SOURCES=Map.of(
            "java",load("Main.java.txt"),"cpp",load("main.cpp.txt"),"python",load("main.py.txt"));
    private AcmStarterTemplates() {}
    static String code(String language) {
        String source=SOURCES.get(language);
        if(source==null)throw new IllegalArgumentException("Unsupported starter language");
        return source;
    }
    private static String load(String filename) {
        try(var input=new ClassPathResource("templates/acm/"+filename).getInputStream()) {
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        } catch(IOException e) {throw new UncheckedIOException("Cannot load ACM starter",e);}
    }
}
