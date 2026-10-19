package io.github.markusluisflores.frontrow.service;

import java.util.UUID;

/** What a release did: how many live rows it released and how many lapsed rows it expired (spec §5, Release). */
public record ReleaseResult(UUID holdGroupId, int released, int expired) {}
