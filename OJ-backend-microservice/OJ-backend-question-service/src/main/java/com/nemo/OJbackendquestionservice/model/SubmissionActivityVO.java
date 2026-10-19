package com.nemo.OJbackendquestionservice.model;

import java.util.List;

/** Personal daily counts only: no submission IDs, code or other users' data. */
public record SubmissionActivityVO(String month, String today, String timezone, List<Day> days) {
    public record Day(String date, int submissions, int problems) {}
}
