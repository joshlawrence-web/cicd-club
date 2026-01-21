package com.socotra.deployment.customer;

import com.socotra.coremodel.*;
import com.socotra.deployment.DataFetcher;
import com.socotra.deployment.DataFetcherFactory;
import com.socotra.deployment.customer.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.socotra.platform.tools.ULID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ItemCarePreCommitPlugin implements PreCommitPlugin {

        private static final Logger log = LoggerFactory.getLogger(ItemCarePreCommitPlugin.class);

    private static final String TIMEZONE = "America/Phoenix";

    /**
     * Quote PreCommit - New Business Processing
     * 1. Calculate policy start/end dates
     * 2. Calculate peril dates for each exposure/peril
     * 3. Populate contract term end date
     * 4. Populate expected renewal date
     */
    @Override
    public ItemCareQuote preCommit(ItemCareQuoteRequest request) {
        ItemCareQuote quote = request.quote();
        ItemCareQuote.ItemCareQuoteData data = quote.data();

        // Get wait period and period of cover
        Integer waitPeriod = data.newBusinessWaitPeriod() != null ? data.newBusinessWaitPeriod() : 0;
        Integer periodOfCover = data.periodOfCover() != null ? data.periodOfCover() : 12;

        // 1. Calculate policy start date (today + wait period days)
        ZonedDateTime today = ZonedDateTime.now(ZoneId.of(TIMEZONE)).truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime policyStart = today.plusDays(waitPeriod);
        Instant policyStartInstant = policyStart.toInstant();

        // Calculate policy end date (start + period of cover months)
        ZonedDateTime policyEnd = policyStart.plusMonths(periodOfCover);
        Instant policyEndInstant = policyEnd.toInstant();

        // 3. Contract term end date (policy end - 1 second)
        OffsetDateTime contractTermEndDate = policyEnd.minusSeconds(1).toOffsetDateTime();

        // 4. Expected renewal date (same as policy end date)
        LocalDate expectedRenewalDate = policyEnd.toLocalDate();

        // Peril end date (policy end - 1 second, as LocalDate)
        LocalDate perilEndDate = policyEnd.minusSeconds(1).toLocalDate();
        LocalDate perilStartDate = policyStart.toLocalDate();

        // Build updated data with contract term end date and expected renewal date
        ItemCareQuote.ItemCareQuoteData updatedData = data.toBuilder()
                .contractTermEndDate(contractTermEndDate)
                .expectedRenewalDate(expectedRenewalDate)
                .build();

        // 2. Update peril dates for each item/coverage
        List<ItemQuote> updatedItems = new ArrayList<>();
        for (ItemQuote item : quote.items()) {
            ItemQuote updatedItem = updateItemPerilDates(item, perilStartDate, perilEndDate, data);
            updatedItems.add(updatedItem);
        }

        // Build and return updated quote
        return quote.toBuilder()
                .startTime(policyStartInstant)
                .endTime(policyEndInstant)
                .data(updatedData)
                .items(updatedItems)
                .build();
    }

    /**
     * Updates peril dates for all coverages on an item
     */
    private ItemQuote updateItemPerilDates(ItemQuote item, LocalDate perilStartDate, LocalDate perilEndDate,
            ItemCareQuote.ItemCareQuoteData policyData) {
        ItemQuote.ItemQuoteBuilder builder = item.toBuilder();

        // Update each coverage's peril dates if present
        if (item.accidentalDamage() != null) {
            builder.accidentalDamage(updateAccidentalDamagePerilDates(item.accidentalDamage(),
                    perilStartDate, perilEndDate));
        }
        if (item.breakdown() != null) {
            builder.breakdown(updateBreakdownPerilDates(item.breakdown(), item.data(), perilStartDate,
                    perilEndDate));
        }
        if (item.loss() != null) {
            builder.loss(updateLossPerilDates(item.loss(), perilStartDate, perilEndDate));
        }
        if (item.theft() != null) {
            builder.theft(updateTheftPerilDates(item.theft(), perilStartDate, perilEndDate));
        }
        if (item.replacement() != null) {
            builder.replacement(updateReplacementPerilDates(item.replacement(), item.data(), perilStartDate,
                    perilEndDate));
        }
        if (item.replacementRepair() != null) {
            builder.replacementRepair(updateReplacementRepairPerilDates(item.replacementRepair(),
                    perilStartDate, perilEndDate));
        }
        if (item.replacementWO() != null) {
            builder.replacementWO(updateReplacementWOPerilDates(item.replacementWO(), perilStartDate,
                    perilEndDate));
        }
        if (item.repeatFaultThree() != null) {
            builder.repeatFaultThree(updateRepeatFaultThreePerilDates(item.repeatFaultThree(),
                    perilStartDate, perilEndDate));
        }
        if (item.accessories() != null) {
            builder.accessories(
                    updateAccessoriesPerilDates(item.accessories(), perilStartDate, perilEndDate));
        }

        return builder.build();
    }

    /**
     * For Breakdown coverage: if item is in warranty and in good working order,
     * peril start date is end of warranty + 1 day
     */
    private BreakdownQuote updateBreakdownPerilDates(BreakdownQuote coverage, ItemQuote.ItemQuoteData itemData,
            LocalDate defaultStartDate, LocalDate perilEndDate) {
        LocalDate perilStartDate = defaultStartDate;

        // Special rule: if in warranty and in good working order, start after warranty
        // ends
        Boolean inWarranty = itemData.itemInWarrantyAtTakeup();
        Boolean inWorkingOrder = itemData.itemInWorkingOrderAtTakeup();

        if (Boolean.TRUE.equals(inWarranty) && Boolean.TRUE.equals(inWorkingOrder)) {
            // Calculate end of warranty date: purchase date + manufacturer labour guarantee
            // period
            LocalDate purchaseDate = itemData.purchaseDate();
            Integer labourGuaranteePeriod = itemData.manufacturerLabourGuaranteePeriod();

            if (purchaseDate != null && labourGuaranteePeriod != null) {
                LocalDate warrantyEndDate = purchaseDate.plusMonths(labourGuaranteePeriod);
                perilStartDate = warrantyEndDate.plusDays(1);
            }
        }

        return coverage.toBuilder()
                .data(coverage.data().toBuilder()
                        .perilStartDate(perilStartDate)
                        .perilEndDate(perilEndDate)
                        .build())
                .build();
    }

    /**
     * For Replacement coverage: same logic as Breakdown
     */
    private ReplacementQuote updateReplacementPerilDates(ReplacementQuote coverage,
            ItemQuote.ItemQuoteData itemData,
            LocalDate defaultStartDate, LocalDate perilEndDate) {
        LocalDate perilStartDate = defaultStartDate;

        Boolean inWarranty = itemData.itemInWarrantyAtTakeup();
        Boolean inWorkingOrder = itemData.itemInWorkingOrderAtTakeup();

        if (Boolean.TRUE.equals(inWarranty) && Boolean.TRUE.equals(inWorkingOrder)) {
            LocalDate purchaseDate = itemData.purchaseDate();
            Integer labourGuaranteePeriod = itemData.manufacturerLabourGuaranteePeriod();

            if (purchaseDate != null && labourGuaranteePeriod != null) {
                LocalDate warrantyEndDate = purchaseDate.plusMonths(labourGuaranteePeriod);
                perilStartDate = warrantyEndDate.plusDays(1);
            }
        }

        return coverage.toBuilder()
                .data(coverage.data().toBuilder()
                        .perilStartDate(perilStartDate)
                        .perilEndDate(perilEndDate)
                        .build())
                .build();
    }

    private AccidentalDamageQuote updateAccidentalDamagePerilDates(AccidentalDamageQuote coverage,
            LocalDate perilStartDate, LocalDate perilEndDate) {
        return coverage.toBuilder()
                .data(coverage.data().toBuilder()
                        .perilStartDate(perilStartDate)
                        .perilEndDate(perilEndDate)
                        .build())
                .build();
    }

    private LossQuote updateLossPerilDates(LossQuote coverage, LocalDate perilStartDate, LocalDate perilEndDate) {
        return coverage.toBuilder()
                .data(coverage.data().toBuilder()
                        .perilStartDate(perilStartDate)
                        .perilEndDate(perilEndDate)
                        .build())
                .build();
    }

    private TheftQuote updateTheftPerilDates(TheftQuote coverage, LocalDate perilStartDate,
            LocalDate perilEndDate) {
        return coverage.toBuilder()
                .data(coverage.data().toBuilder()
                        .perilStartDate(perilStartDate)
                        .perilEndDate(perilEndDate)
                        .build())
                .build();
    }

    private ReplacementRepairQuote updateReplacementRepairPerilDates(ReplacementRepairQuote coverage,
            LocalDate perilStartDate, LocalDate perilEndDate) {
        return coverage.toBuilder()
                .data(coverage.data().toBuilder()
                        .perilStartDate(perilStartDate)
                        .perilEndDate(perilEndDate)
                        .build())
                .build();
    }

    private ReplacementWOQuote updateReplacementWOPerilDates(ReplacementWOQuote coverage,
            LocalDate perilStartDate, LocalDate perilEndDate) {
        return coverage.toBuilder()
                .data(coverage.data().toBuilder()
                        .perilStartDate(perilStartDate)
                        .perilEndDate(perilEndDate)
                        .build())
                .build();
    }

    private RepeatFaultThreeQuote updateRepeatFaultThreePerilDates(RepeatFaultThreeQuote coverage,
            LocalDate perilStartDate, LocalDate perilEndDate) {
        return coverage.toBuilder()
                .data(coverage.data().toBuilder()
                        .perilStartDate(perilStartDate)
                        .perilEndDate(perilEndDate)
                        .build())
                .build();
    }

    private AccessoriesQuote updateAccessoriesPerilDates(AccessoriesQuote coverage,
            LocalDate perilStartDate, LocalDate perilEndDate) {
        return coverage.toBuilder()
                .data(coverage.data().toBuilder()
                        .perilStartDate(perilStartDate)
                        .perilEndDate(perilEndDate)
                        .build())
                .build();
    }

    @Override
    public PreCommitTransactionResponse preCommit(ItemCareTransactionRequest request) {
        log.info("PreCommit called for transaction type: {}", request.transaction().transactionType());
        log.info("Incoming change instructions count: {}", request.changeInstructions().size());

        var builder = PreCommitTransactionResponse.builder();

        if (TransactionType.POSDiscount.name().equals(request.transaction().transactionType())) {
            log.info("Processing POSDiscount transaction");

            ZonedDateTime policyStartZoned = request.policy().startTime()
                    .atZone(ZoneId.of(request.policy().timezone()));
            var effectiveDate = policyStartZoned.plusMonths(6).toInstant();
            log.info("Calculated effective date: {}", effectiveDate);

            // Update effective date in existing params change instruction
            boolean foundParamsInstruction = false;
            for (ChangeInstructionHolder holder : request.changeInstructions()) {
                log.info("Processing change instruction - paramsInstruction present: {}, modifyInstruction present: {}",
                        holder.paramsInstruction().isPresent(),
                        holder.modifyInstruction().isPresent());

                if (holder.paramsInstruction().isPresent()) {
                    foundParamsInstruction = true;
                    log.info("Found existing paramsInstruction with effectiveTime: {}",
                            holder.paramsInstruction().get().effectiveTime());

                    // Modify the existing params instruction with the new effective date
                    ChangeInstructionHolder updatedParamsHolder = ChangeInstructionHolder.builder()
                            .paramsInstruction(holder.paramsInstruction().get().toBuilder()
                                    .effectiveTime(effectiveDate)
                                    .build())
                            .build();
                    builder.addChangeInstruction(updatedParamsHolder);
                    log.info("Updated paramsInstruction with new effectiveTime: {}", effectiveDate);
                } else {
                    // Keep other change instructions as-is
                    builder.addChangeInstruction(holder);
                    log.info("Passing through non-params change instruction");
                }
            }

            log.info("Found params instruction: {}", foundParamsInstruction);

            // Get segment from the base transaction (the transaction this one is based on)
            ItemCareSegment segment = null;
            if (request.transaction().baseTransactionLocator().isPresent()) {
                var baseTransactionLocator = request.transaction().baseTransactionLocator().get();
                log.info("Base transaction locator: {}", baseTransactionLocator);
                segment = (ItemCareSegment) DataFetcherFactory.get()
                        .getSegmentByTransaction(baseTransactionLocator);
            } else {
                log.info("No base transaction locator found");
            }

            log.info("Segment lookup result: {}", segment != null ? "found" : "null");

            if (segment != null) {
                log.info("Adding modifyInstruction for staticLocator: {}", segment.element().staticLocator());
                ChangeInstructionHolder modifyHolder = ChangeInstructionHolder.builder()
                        .modifyInstruction(ModifyChangeInstruction.builder()
                                .staticLocator(segment.element().staticLocator())
                                .removeData(Map.of(
                                        "discountProfileCode", "remove",
                                        "discountTerm", "remove",
                                        "discountAmount", "remove",
                                        "discountType", "remove"))
                                .build())
                        .build();
                builder.addChangeInstruction(modifyHolder);
            }
        } else {
            log.info("Non-POSDiscount transaction, passing through change instructions");
            // For non-POSDiscount transactions, pass through all change instructions
            // unchanged
            builder.addChangeInstructions(request.changeInstructions());
        }

        PreCommitTransactionResponse response = builder.build();
        log.info("Returning response with {} change instructions", response.changeInstructions().size());
        return response;
    }

    /**
     * 2) DELINQUENCY PRE‑COMMIT
     *
     * This method is called automatically by Billing when a delinquency moves into
     * the inGrace state. You can:
     *   * Override graceEndAt (absolute timestamp), OR
     *   * Adjust settings.gracePeriodDays, lapseTransactionType, etc.
     *
     * The docs’ example sets a new graceEndAt directly; here we do a slightly
     * richer pattern so you can plug in business rules.
     */
    @Override
    public PreCommitDelinquencyResponse preCommit(DelinquencyRequest request) {
        var delinquency = request.delinquency();
        var settings    = delinquency.settings();

        // 1) Unwrap grace start (we know it exists)
        Instant graceStart = delinquency.graceStartedAt().orElseThrow();

        // 2) Unwrap grace end (this is our default if we don't override)
        Instant newGraceEnd = delinquency.graceEndAt().orElse(null);

        // --- 1) Find policy locator from references ---
        var references = delinquency.references();
        ULID policyLocator = null;

        if (references != null) {
            for (var ref : references) {
                // referenceType is an enum; .name() is fine
                if ("policy".equalsIgnoreCase(ref.referenceType().name())) {
                    policyLocator = ref.referenceLocator();
                    break;
                }
            }
        }

        // If we can't resolve a policy, just return the current graceEndAt
        if (policyLocator == null) {
            return PreCommitDelinquencyResponse.builder()
                    .graceEndAt(newGraceEnd)
                    .settings(settings)
                    .build();
        }

        // --- 2) Fetch policy static data ---
        DataFetcher dataFetcher = DataFetcherFactory.get();
        var policy = dataFetcher.getPolicy(policyLocator);  // fine even if you don't use it directly

        @SuppressWarnings("unchecked")
        Map<String, Object> staticData =
                (Map<String, Object>) dataFetcher.getPolicyStaticData(policyLocator);

        // --- 3) Pull gracePeriod (number) safely ---
        Integer gracePeriod = null;
        Object rawGrace = staticData.get("gracePeriod");
        if (rawGrace instanceof Number n) {
            gracePeriod = n.intValue();
        }

        // --- 4) Pull gracePeriodType (string) safely ---
        String gracePeriodType = null;
        Object rawType = staticData.get("gracePeriodType");
        if (rawType instanceof String s) {
            gracePeriodType = s;
        }

        // If either is missing, keep the existing graceEndAt and exit
        if (gracePeriod == null || gracePeriodType == null) {
            return PreCommitDelinquencyResponse.builder()
                    .graceEndAt(newGraceEnd)
                    .settings(settings)
                    .build();
        }

        // --- 5) Override graceEndAt ONLY when we have both fields ---

        switch (gracePeriodType) {
            case "M" -> newGraceEnd = graceStart.plus(gracePeriod.longValue(), ChronoUnit.MONTHS);
            case "D" -> newGraceEnd = graceStart.plus(gracePeriod.longValue(), ChronoUnit.DAYS);
            // anything else, leave newGraceEnd unchanged
        }

        // --- 6) Return, always using newGraceEnd (either original or overridden) ---
        return PreCommitDelinquencyResponse.builder()
                .graceEndAt(newGraceEnd)
                .settings(settings)
                .build();
    }
}
