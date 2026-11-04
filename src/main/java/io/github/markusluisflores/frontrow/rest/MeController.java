package io.github.markusluisflores.frontrow.rest;

import io.github.markusluisflores.frontrow.service.HeldGroup;
import io.github.markusluisflores.frontrow.service.OrderResult;
import io.github.markusluisflores.frontrow.service.OwnerReadService;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A buyer's own holds and orders (spec §6). The owner is the authenticated user's name, never a parameter (spec §3), and
 * the service filters by it: the role table only admits BUYER.
 */
@RestController
public class MeController {

    private final OwnerReadService reads;

    public MeController(OwnerReadService reads) {
        this.reads = reads;
    }

    /** Own live holds, including the ones the user's agent made: they are one owner (spec §6, the handover). */
    @GetMapping("/api/me/holds")
    public List<HeldGroup> holds(Principal principal) {
        return reads.holds(principal.getName());
    }

    @GetMapping("/api/me/orders")
    public List<OrderResult> orders(Principal principal) {
        return reads.orders(principal.getName());
    }
}
