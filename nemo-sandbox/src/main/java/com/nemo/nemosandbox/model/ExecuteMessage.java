package com.nemo.nemosandbox.model;

import lombok.Data;

@Data
public class ExecuteMessage {

    String message;

    String errorMessage;

    Integer exitValue;

    Long time;

    Long memory;
}
