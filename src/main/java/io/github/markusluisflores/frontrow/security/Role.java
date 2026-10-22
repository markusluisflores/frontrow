package io.github.markusluisflores.frontrow.security;

/** The three REST roles (spec §9). An agent token never carries one: the MCP chain grants only AGENT (spec §3). */
public enum Role {
    BUYER,
    ORGANISER,
    ADMIN
}
