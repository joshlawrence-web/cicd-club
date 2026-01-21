package com.socotra.deployment.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socotra.coremodel.*;
import com.socotra.deployment.DataFetcher;
import com.socotra.platform.tools.ULID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class ItemCareCancellationPluginBackup implements CancellationPlugin {

    private static final Logger log = LoggerFactory.getLogger(ItemCareCancellationPluginBackup.class);

    @Override
    public CancellationPluginResponse cancel(ItemCareRequest request) {

        Transaction transaction = request.transaction();
        ItemCareSegment currentSegment = request.segment();
        BigDecimal MINIMUM_EARNED_PREMIUM = BigDecimal.valueOf(100L);

        log.info("Cancellation plugin invoked for transaction {} on policy {}",
                request.transaction().locator(),
                request.policy().locator());

        // Get cancellation charges from request---------------------
        Collection<Charge> cancellationCharges = request.charges();
        log.info("=== CANCELLATION CHARGES ===");
        log.info("Total cancellation charges count: {}", cancellationCharges.size());
        cancellationCharges.forEach(charge -> {
            log.info("Charge: category={}, amount={}, locator={}",
                    charge.chargeCategory(),
                    charge.amount(),
                    charge.locator());
        });

        Collection<Charge> premiumCancellationCharges = cancellationCharges.stream()
                .filter(c -> c.chargeCategory().equals(ChargeCategory.premium) ||
                        c.chargeCategory().equals(ChargeCategory.tax) ||
                        c.chargeCategory().equals(ChargeCategory.surcharge))
                .toList();
        log.info("Premium cancellation charges: {}", premiumCancellationCharges);
        //-------------------------------------------------------------


        // Get all term charges-------------------------------------------
        Map<ULID, Collection<Charge>> termCharges = DataFetcher.getInstance()
                .getTermCharges(request.policy().locator());

        log.info("=== TERM CHARGES ===");
        log.info("Term charges map size: {}", termCharges.size());
        termCharges.forEach((ulid, charges) -> {
            log.info("Transaction {}: {} charges", ulid, charges.size());
            charges.forEach(charge -> {
                log.info("  Charge: category={}, amount={}",
                        charge.chargeCategory(),
                        charge.amount());
            });
        });

        Collection<Charge> premiumCharges = termCharges.values().stream()
                .flatMap(Collection::stream)
                .filter(c -> c.chargeCategory().equals(ChargeCategory.premium) ||
                        c.chargeCategory().equals(ChargeCategory.tax) ||
                        c.chargeCategory().equals(ChargeCategory.surcharge))
                .toList();
        log.info("Premium charges on term: {}", premiumCharges);
        //--------------------------------






        // Parse cancellation comments
        String policyLocator = request.policy().locator().toString();
        AuxData cancellationCommentsAux = DataFetcher.getInstance()
                .getAuxData(policyLocator, "cancellationComments");

        CancellationCommentsData commentsData = null;
        if (cancellationCommentsAux != null) {
            String cancellationComments = cancellationCommentsAux.value();
            log.info("Found cancellationComments auxData: {}", cancellationComments);

            try {
                ObjectMapper mapper = new ObjectMapper();
                commentsData = mapper.readValue(cancellationComments, CancellationCommentsData.class);
                log.info("Successfully parsed cancellationComments: {}", commentsData);
            } catch (Exception e) {
                log.error("Failed to parse cancellationComments JSON", e);
            }
        } else {
            log.info("No cancellationComments auxData found for policy {}", policyLocator);
        }

        // Sum all issued premiums
        BigDecimal totalPremium = premiumCharges.stream()
                .map(Charge::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Sum all cancellation premiums
        BigDecimal totalCancellation = premiumCancellationCharges.stream()
                .map(Charge::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Calculate earned (cancellation is negative, so add it)
        BigDecimal earnedPremium = totalPremium.add(totalCancellation);

        // Setup values for calculation
        BigDecimal annualPremium = totalPremium;
        BigDecimal totalCollected = totalPremium.subtract(totalCancellation.abs());

        // Variable to store final settlement amount
        BigDecimal settlementAmount = BigDecimal.ZERO;

        // Rule 1: Externally Calculated Settlement Amount (highest priority)
        if (commentsData != null && Boolean.TRUE.equals(commentsData.getExternallyCalculatedSettlementAmount())) {
            settlementAmount = commentsData.getSettlementAmount();
            BigDecimal taxAmount = commentsData.getTaxTotalAmount(); // TODO: Apply tax in separate charge
            log.info("Rule 1: Using externally calculated settlementAmount: {}, tax: {}", settlementAmount, taxAmount);
            return buildResponse(currentSegment, settlementAmount);
        }

        BigDecimal claimCost = commentsData != null ? commentsData.getClaimCost() : null;

        // Rule 2: Fixed Price Repair
        if (commentsData != null && commentsData.getIsPartialRefund() != null) {
            if (Boolean.TRUE.equals(commentsData.getIsPartialRefund())) {
                // Partial refund: charge the difference
                settlementAmount = commentsData.getDefaultCharge().subtract(totalCollected);
                log.info("Rule 2: Partial refund - defaultCharge({}) - totalCollected({}) = {}",
                        commentsData.getDefaultCharge(), totalCollected, settlementAmount);
            } else {
                // Full refund: refund everything collected
                settlementAmount = totalCollected.multiply(BigDecimal.valueOf(-1));
                log.info("Rule 2: Full refund - totalCollected({}) * -1 = {}",
                        totalCollected, settlementAmount);
            }
            return buildResponse(currentSegment, settlementAmount);
        }



        // Rule 3: Settlement Processing (Fixed Term only)
        if (commentsData != null &&
                claimCost != null &&
                "FixedTerm".equals(request.segment().data().productTerm())) {

            log.info("Rule 3: Settlement Processing (Fixed Term)");

            // Initialize flavour flags
            boolean settlementLimitExists = false;
            boolean settlementAmountExists = false;

            // Check which flavour we have
            // Flavour A: settlementLimit AND claimCost
            if (commentsData.getSettlementLimit() != null) {
                settlementLimitExists = true;
                log.info("Flavour A: settlementLimit + claimCost");
            }
            // Flavour B: settlementAmount AND claimCost
            else if (commentsData.getSettlementAmount() != null) {
                settlementAmountExists = true;
                log.info("Flavour B: settlementAmount + claimCost");
            }
            // Flavour C: Only claimCost
            else {
                log.info("Flavour C: Only claimCost");
            }

            log.info("Setup: claimCost={}, annualPremium={}, totalCollected={}",
                    claimCost, annualPremium, totalCollected);

            // Flavour A: settlementLimit calculation
            if (settlementLimitExists) {
                // Step 1: min(annualPremium, claimCost)
                BigDecimal mathStep1 = annualPremium.min(claimCost);
                log.info("MathStep1: min(annualPremium={}, claimCost={}) = {}",
                        annualPremium, claimCost, mathStep1);

                // Step 2: max(totalCollected, mathStep1)
                BigDecimal mathStep2 = totalCollected.max(mathStep1);
                log.info("MathStep2: max(totalCollected={}, mathStep1={}) = {}",
                        totalCollected, mathStep1, mathStep2);

                // Step 3: mathStep2 - totalCollected
                BigDecimal mathStep3 = mathStep2.subtract(totalCollected);
                log.info("MathStep3: mathStep2={} - totalCollected={} = {}",
                        mathStep2, totalCollected, mathStep3);

                // Step 4: min(mathStep3, settlementLimit)
                BigDecimal settlementLimit = commentsData.getSettlementLimit();
                settlementAmount = mathStep3.min(settlementLimit);
                log.info("MathStep4: min(mathStep3={}, settlementLimit={}) settlementAmount= {}",
                        mathStep3, settlementLimit, settlementAmount);


            }

            // Flavour B: settlementAmount check
            if (settlementAmountExists) {
                if (totalCollected.compareTo(claimCost) > 0) {
                    settlementAmount = BigDecimal.ZERO;
                    log.info("Flavour B: totalCollected > claimCost, settlementAmount = 0");
                } else {
                    settlementAmount = commentsData.getSettlementAmount();
                    log.info("Flavour B: Using provided settlementAmount = {}", settlementAmount);
                }

            }

            // Flavour C: Only claimCost (fallback calculation)
            if (!settlementLimitExists && !settlementAmountExists) {
                log.info("Flavour C: Only claimCost - fallback calculation");

                // Line 1: Start with outstandingPremium = 0
                BigDecimal outstandingPremium = BigDecimal.ZERO;
                log.info("Line 1: Initialize outstandingPremium = {}", outstandingPremium);

                // Line 2: Check if Annual Premium > Total Collected
                if (annualPremium.compareTo(totalCollected) > 0) {
                    // Line 3: Calculate what customer still owes
                    outstandingPremium = annualPremium.subtract(totalCollected);
                    log.info("Line 3: outstandingPremium = annualPremium({}) - totalCollected({}) = {}",
                            annualPremium, totalCollected, outstandingPremium);
                }

                // Line 4: Pick the biggest - totalCollected OR claimCost
                BigDecimal maxValue = totalCollected.max(claimCost);
                log.info("Line 4: maxValue = max(totalCollected={}, claimCost={}) = {}",
                        totalCollected, claimCost, maxValue);

                // Line 5: Subtract what's already paid
                BigDecimal amountNeeded = maxValue.subtract(totalCollected);
                log.info("Line 5: amountNeeded = maxValue({}) - totalCollected({}) = {}",
                        maxValue, totalCollected, amountNeeded);

                // Line 6: Cap it at outstandingPremium
                settlementAmount = amountNeeded.min(outstandingPremium);
                log.info("Line 6: settlementAmount = min(amountNeeded={}, outstandingPremium={}) = {}",
                        amountNeeded, outstandingPremium, settlementAmount);


            }
        }
        // After all rules have calculated settlementAmount...

        boolean collectPlanFee = commentsData != null && Boolean.TRUE.equals(commentsData.getCollectPlanFeeFlag());
        boolean settlementCheck = commentsData != null && Boolean.TRUE.equals(commentsData.getSettlementCheckFlag());
        boolean refundCheck = commentsData != null && Boolean.TRUE.equals(commentsData.getRefundCheckFlag());

        boolean isLimitOrAmountBased =
                commentsData != null &&
                        (commentsData.getSettlementLimit() != null || commentsData.getSettlementAmount() != null);

        BigDecimal zero = BigDecimal.ZERO;

        // Special override: settlement method is limit/amount-based
        if (collectPlanFee && settlementCheck && isLimitOrAmountBased) {
            log.info("Final ladder: special settlement flavour; settlementAmount={}", settlementAmount);
        }

        // Collect plan fee: charge up to annual premium
        else if (collectPlanFee) {

            BigDecimal amountNeeded = annualPremium.subtract(totalCollected);
            settlementAmount = amountNeeded.max(zero);

            log.info("Final ladder: collectPlanFee; amountNeeded={}, settlementAmount={}",
                    amountNeeded, settlementAmount);
        }

        // Settlement path
        else if (settlementCheck && settlementAmount.compareTo(zero) > 0) {
            log.info("Final ladder: settlementCheck + positive settlementAmount={}", settlementAmount);
        }

        // Refund path
        else if (refundCheck && settlementAmount.compareTo(zero) <= 0) {

            String productTerm = request.segment().data().productTerm();

            // claimCost: use prorated earned premium as "cost of coverage used"
            // Settlement is the additional amount needed up to claimCost (floor at 0).
            if (claimCost != null) {

                BigDecimal additionalAmountNeeded = claimCost.subtract(earnedPremium);

                if (additionalAmountNeeded.compareTo(zero) > 0) {
                    settlementAmount = additionalAmountNeeded;
                } else {
                    settlementAmount = zero;
                }

                log.info("Final ladder: subscription+claimCost; claimCost={} - earnedPremium={} => settlementAmount={}",
                        claimCost, earnedPremium, settlementAmount);

            } else {
                // Non-subscription (or no claimCost): keep your current simplified behavior
                BigDecimal totalCostOfCoveragePremium = annualPremium;
                settlementAmount = totalCostOfCoveragePremium.subtract(totalCollected);

                log.info("Final ladder: refundCheck; claimCost={}, totalCostOfCoveragePremium={}, settlementAmount={}",
                        claimCost, totalCostOfCoveragePremium, settlementAmount);
            }
        }

        // Default
        else {
            settlementAmount = zero;
            log.info("Final ladder: default; settlementAmount=0");
        }

        // Final return
        return buildResponse(currentSegment, settlementAmount);



    }

    private CancellationPluginResponse buildResponse(ItemCareSegment currentSegment, BigDecimal settlementAmount) {
        return CancellationPluginResponse.builder()
                .retentionCharges(
                        RatingSet.builder()
                                .ok(true)
                                .ratingItems(List.of(
                                        RatingItem.builder()
                                                .elementLocator(currentSegment.element().locator())
                                                .chargeType(ChargeType.settlement)
                                                .amount(settlementAmount)
                                                .build()
                                ))
                                .build()
                )
                .build();
    }

    
}