package io.github.markusluisflores.frontrow.service;

import java.time.LocalDate;

/**
 * search_events and GET /api/events (spec §6). Every field is optional: query matches the name, the dates bound
 * starts_at inclusively in UTC, and limit defaults to 10, with 50 the most.
 */
public record EventQuery(String query, LocalDate fromDate, LocalDate toDate, Integer limit) {}
