package io.github.markusluisflores.frontrow.security;

/**
 * The two REST roles (spec §9; revision 2.7 cut ADMIN). An agent token never carries one: the MCP chain grants only
 * AGENT (spec §3).
 */
public enum Role {
    BUYER,
    ORGANISER
}
