package com.worktrack.dto;

public record NotificationSummaryResponse(long pending, long processing, long sent, long failed) {
}
