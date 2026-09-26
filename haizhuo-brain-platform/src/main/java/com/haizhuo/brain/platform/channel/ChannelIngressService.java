package com.haizhuo.brain.platform.channel;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.EmployeeCatalog;
import com.haizhuo.brain.platform.employee.PublishedEmployee;
import java.util.Objects;

/** Platform validation and routing before any inbox record or Agent execution. */
public final class ChannelIngressService {
    private final ChannelAccountDirectory accounts;
    private final ChannelIdentityDirectory identities;
    private final EmployeeCatalog employees;
    private final ChannelTurnStore turns;

    public ChannelIngressService(ChannelAccountDirectory accounts, ChannelIdentityDirectory identities,
                                 EmployeeCatalog employees, ChannelTurnStore turns) {
        this.accounts = Objects.requireNonNull(accounts);
        this.identities = Objects.requireNonNull(identities);
        this.employees = Objects.requireNonNull(employees);
        this.turns = Objects.requireNonNull(turns);
    }

    public ChannelAcceptance accept(VerifiedChannelMessage message) {
        Objects.requireNonNull(message);
        ChannelAccountBinding binding = accounts.findById(message.bindingId())
                .orElseThrow(() -> new IllegalArgumentException("Channel account is not bound"));
        if (!binding.enabled() || !binding.provider().equals(message.provider())) {
            throw new IllegalArgumentException("Channel account is disabled or provider mismatched");
        }
        UserId userId = identities.resolve(binding.tenantId(), binding.bindingId(), message.externalUserId())
                .orElseThrow(() -> new IllegalArgumentException("Channel user is not linked"));
        PublishedEmployee published = employees.findPublished(binding.tenantId(), binding.defaultEmployeeId())
                .orElseThrow(() -> new IllegalStateException("Digital employee has no published definition"));
        if (!published.employee().enabled()
                || !published.employee().tenantId().equals(binding.tenantId())
                || published.employee().id() != binding.defaultEmployeeId()
                || published.definition().employeeId() != binding.defaultEmployeeId()) {
            throw new IllegalStateException("Digital employee is unavailable");
        }
        return turns.accept(message, binding, userId);
    }
}
