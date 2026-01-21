package com.socotra.deployment.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socotra.coremodel.*;
import com.socotra.deployment.DataFetcher;
import com.socotra.platform.tools.ULID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public class ItemCareCancellationPlugin implements CancellationPlugin {

    private static final Logger log = LoggerFactory.getLogger(ItemCareCancellationPlugin.class);

    /**
     * Stable numeric inputs for rule evaluation. All amounts here are NET premium (no tax),
     * except taxRate which is a ratio used to compute tax at the very end.
     */
    private record Inputs(
            BigDecimal annualPremiumNet,
            BigDecimal totalCollectedNet,
            BigDecimal earnedPremiumNet,
            BigDecimal taxRate
    ) {}

    @Override
    public CancellationPluginResponse cancel(ItemCareRequest request) {

        ItemCareSegment currentSegment = request.segment();
        String policyLocator = request.policy().locator().toString();

        log.info("Cancellation plugin invoked for transaction {} on policy {}",
                request.transaction().locator(),
                request.policy().locator());

        Inputs in = buildInputs(request, policyLocator);
        CancellationCommentsData commentsData = loadCancellationComments(policyLocator);

        // Rule 1: externally calculated gross, full stop
        if (commentsData != null && Boolean.TRUE.equals(commentsData.getExternallyCalculatedSettlementAmount())) {
            BigDecimal externalGross = nvl(commentsData.getSettlementAmount());
            log.info("Rule 1: Using externally calculated gross settlementAmount (no recompute): {}", externalGross);
            return buildResponse(currentSegment, externalGross);
        }

        // Rule 2: fixed price repair (net)
        Optional<BigDecimal> fixedNet = fixedPriceRepairNet(commentsData, in);

        // Rule 3: settlement processing (net) if not fixed price
        BigDecimal calculatedNet = fixedNet.orElseGet(() -> settlementProcessingNet(commentsData, in));

        // Final ladder (net)
        BigDecimal netSettlement = finalLadderNet(commentsData, in, calculatedNet);

        // Tax once at the end (gross output)
        BigDecimal grossSettlement = addRoundedTax(netSettlement, in.taxRate());

        log.info("Final settlement: netPremium={}, taxRate={}, grossSettlement={}",
                netSettlement, in.taxRate(), grossSettlement);

        return buildResponse(currentSegment, grossSettlement);
    }

    private CancellationPluginResponse buildResponse(ItemCareSegment currentSegment, BigDecimal settlementAmount) {
        return CancellationPluginResponse.builder()
                .retentionCharges(
                        RatingSet.builder()
                                .ok(true)
                                .ratingItems(java.util.List.of(
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

    /**
     * Build NET inputs (premium only) and tax rate from paid invoice totals auxData.
     */
    private Inputs buildInputs(ItemCareRequest request, String policyLocator) {
        Collection<Charge> cancellationCharges = request.charges();
        log.info("=== CANCELLATION CHARGES === count={}", cancellationCharges.size());

        Map<ULID, Collection<Charge>> termCharges = DataFetcher.getInstance()
                .getTermCharges(request.policy().locator());
        log.info("=== TERM CHARGES === txCount={}", termCharges.size());

        // --- Read paid invoice totals ---
        BigDecimal totalPremiumCollectedPaid = BigDecimal.ZERO;
        BigDecimal totalTaxCollectedPaid = BigDecimal.ZERO;

        AuxData paidTotalsAux = DataFetcher.getInstance().getAuxData(policyLocator, "paidInvoiceTotals");
        if (paidTotalsAux != null) {
            String paidTotalsJson = paidTotalsAux.value();
            log.info("Found paidInvoiceTotals auxData: {}", paidTotalsJson);
            try {
                ObjectMapper mapper = new ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(paidTotalsJson);
                totalPremiumCollectedPaid = new BigDecimal(node.path("totalPremiumCollected").asText("0"));
                totalTaxCollectedPaid = new BigDecimal(node.path("totalTaxCollected").asText("0"));
            } catch (Exception e) {
                log.error("Failed to parse paidInvoiceTotals JSON", e);
            }
        } else {
            log.info("No paidInvoiceTotals auxData found for policy {}", policyLocator);
        }

        BigDecimal taxRate = BigDecimal.ZERO;
        if (totalPremiumCollectedPaid.signum() > 0 && totalTaxCollectedPaid.signum() >= 0) {
            taxRate = totalTaxCollectedPaid.divide(totalPremiumCollectedPaid, 10, RoundingMode.HALF_UP);
        }

        // --- Term net issued (premium only) ---
        Collection<Charge> termAllCharges = termCharges.values().stream()
                .flatMap(Collection::stream)
                .toList();

        BigDecimal annualPremiumNet = sumAmounts(
                termAllCharges.stream().filter(ItemCareCancellationPlugin::isNetPremiumLike).toList()
        );

        // Earned net approximation: annual + cancellationNet (cancel charges are negative)
        BigDecimal cancellationNet = sumAmounts(
                cancellationCharges.stream().filter(ItemCareCancellationPlugin::isNetPremiumLike).toList()
        );
        BigDecimal earnedPremiumNet = annualPremiumNet.add(cancellationNet);

        // Rule math uses NET collected premium only (per your pseudo logic)
        BigDecimal totalCollectedNet = totalPremiumCollectedPaid;

        log.info("Inputs: annualPremiumNet={}, earnedPremiumNet={}, totalCollectedNet={}, taxRate={}",
                annualPremiumNet, earnedPremiumNet, totalCollectedNet, taxRate);

        return new Inputs(annualPremiumNet, totalCollectedNet, earnedPremiumNet, taxRate);
    }

    private CancellationCommentsData loadCancellationComments(String policyLocator) {
        AuxData cancellationCommentsAux = DataFetcher.getInstance()
                .getAuxData(policyLocator, "cancellationComments");

        if (cancellationCommentsAux == null) {
            log.info("No cancellationComments auxData found for policy {}", policyLocator);
            return null;
        }

        String cancellationComments = cancellationCommentsAux.value();
        log.info("Found cancellationComments auxData: {}", cancellationComments);

        try {
            ObjectMapper mapper = new ObjectMapper();
            CancellationCommentsData commentsData = mapper.readValue(cancellationComments, CancellationCommentsData.class);
            log.info("Successfully parsed cancellationComments");
            return commentsData;
        } catch (Exception e) {
            log.error("Failed to parse cancellationComments JSON", e);
            return null;
        }
    }

    /**
     * Rule 2 (Fixed Price Repair), NET:
     * - partial refund: defaultCharge - totalCollectedNet
     * - full refund: -totalCollectedNet
     */
    private Optional<BigDecimal> fixedPriceRepairNet(CancellationCommentsData commentsData, Inputs in) {
        if (commentsData == null || commentsData.getIsPartialRefund() == null) {
            return Optional.empty();
        }

        BigDecimal net;
        if (Boolean.TRUE.equals(commentsData.getIsPartialRefund())) {
            net = nvl(commentsData.getDefaultCharge()).subtract(in.totalCollectedNet());
            log.info("Rule 2: Partial refund net = defaultCharge({}) - totalCollectedNet({}) = {}",
                    commentsData.getDefaultCharge(), in.totalCollectedNet(), net);
        } else {
            net = in.totalCollectedNet().negate();
            log.info("Rule 2: Full refund net = totalCollectedNet({}) * -1 = {}", in.totalCollectedNet(), net);
        }

        return Optional.of(net);
    }

    /**
     * Rule 3 (Settlement Processing), NET.
     * Returns "calculated settlementAmount" (net) from Flavour A/B/C.
     */
    private BigDecimal settlementProcessingNet(CancellationCommentsData commentsData, Inputs in) {
        if (commentsData == null || commentsData.getClaimCost() == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal claimCost = nvl(commentsData.getClaimCost());
        log.info("Rule 3: Settlement Processing. claimCost={}, annualPremiumNet={}, totalCollectedNet={}",
                claimCost, in.annualPremiumNet(), in.totalCollectedNet());

        boolean settlementLimitExists =
                commentsData.getSettlementLimit() != null
                        && commentsData.getSettlementLimit().compareTo(BigDecimal.ZERO) > 0;

        boolean settlementAmountExists =
                commentsData.getSettlementAmount() != null
                        && commentsData.getSettlementAmount().compareTo(BigDecimal.ZERO) > 0;

        // Flavour A
        if (settlementLimitExists) {
            log.info("Flavour A: settlementLimit + claimCost");

            BigDecimal mathStep1 = in.annualPremiumNet().min(claimCost);
            BigDecimal mathStep2 = in.totalCollectedNet().max(mathStep1);
            BigDecimal mathStep3 = mathStep2.subtract(in.totalCollectedNet());
            BigDecimal net = mathStep3.min(commentsData.getSettlementLimit());

            log.info("Flavour A net settlement={}", net);
            return net;
        }

        // Flavour B
        if (settlementAmountExists) {
            log.info("Flavour B: settlementAmount + claimCost");
            if (in.totalCollectedNet().compareTo(claimCost) > 0) {
                log.info("Flavour B: totalCollectedNet > claimCost, net=0");
                return BigDecimal.ZERO;
            }
            BigDecimal net = nvl(commentsData.getSettlementAmount());
            log.info("Flavour B net settlement={}", net);
            return net;
        }

        // Flavour C fallback
        log.info("Flavour C: Only claimCost (fallback calculation)");

        BigDecimal outstandingPremium = BigDecimal.ZERO;
        if (in.annualPremiumNet().compareTo(in.totalCollectedNet()) > 0) {
            outstandingPremium = in.annualPremiumNet().subtract(in.totalCollectedNet());
        }

        BigDecimal maxValue = in.totalCollectedNet().max(claimCost);
        BigDecimal amountNeeded = maxValue.subtract(in.totalCollectedNet());
        BigDecimal net = amountNeeded.min(outstandingPremium);

        log.info("Flavour C net settlement={}", net);
        return net;
    }

    /**
     * Final ladder from your pseudo logic, NET.
     */
    private BigDecimal finalLadderNet(CancellationCommentsData commentsData, Inputs in, BigDecimal calculatedNet) {
        BigDecimal net = nvl(calculatedNet);

        boolean collectPlanFee = commentsData != null && Boolean.TRUE.equals(commentsData.getCollectPlanFeeFlag());
        boolean settlementCheck = commentsData != null && Boolean.TRUE.equals(commentsData.getSettlementCheckFlag());
        boolean refundCheck = commentsData != null && Boolean.TRUE.equals(commentsData.getRefundCheckFlag());

        // Only treat as "limit/amount based" if POSITIVE (prevents settlementAmount=0 being treated as a method)
        boolean isLimitOrAmountBased =
                commentsData != null &&
                        ((commentsData.getSettlementLimit() != null && commentsData.getSettlementLimit().compareTo(BigDecimal.ZERO) > 0)
                                || (commentsData.getSettlementAmount() != null && commentsData.getSettlementAmount().compareTo(BigDecimal.ZERO) > 0));

        BigDecimal zero = BigDecimal.ZERO;

        // Special override: settlement method is limit/amount-based
        if (collectPlanFee && settlementCheck && isLimitOrAmountBased) {
            log.info("Final ladder: special settlement flavour (limit/amount-based); net={}", net);
            return net;
        }

        // Collect plan fee: Annual Premium – Total Premium Collected
        if (collectPlanFee) {
            BigDecimal amountNeeded = in.annualPremiumNet().subtract(in.totalCollectedNet());
            net = amountNeeded.max(zero);
            log.info("Final ladder: collectPlanFee; amountNeeded={}, net={}", amountNeeded, net);
            return net;
        }

        // Settlement path
        if (settlementCheck && net.compareTo(zero) > 0) {
            log.info("Final ladder: settlementCheck + positive net={}", net);
            return net;
        }

        // Refund path (claims-cost logic)
        if (refundCheck && net.compareTo(zero) <= 0) {
            BigDecimal claimCost = commentsData != null ? commentsData.getClaimCost() : null;

            BigDecimal costOfCoverageUsed = in.earnedPremiumNet();
            if (costOfCoverageUsed.compareTo(zero) < 0) costOfCoverageUsed = zero;
            if (costOfCoverageUsed.compareTo(in.annualPremiumNet()) > 0) costOfCoverageUsed = in.annualPremiumNet();

            BigDecimal targetCoverage = costOfCoverageUsed;
            if (claimCost != null) {
                targetCoverage = costOfCoverageUsed.max(claimCost).min(in.annualPremiumNet());
            }

            net = targetCoverage.subtract(in.totalCollectedNet());
            log.info("Final ladder: refundCheck; costOfCoverageUsed={}, claimCost={}, annualPremiumNet={}, totalCollectedNet={}, net={}",
                    costOfCoverageUsed, claimCost, in.annualPremiumNet(), in.totalCollectedNet(), net);
            return net;
        }

        log.info("Final ladder: default; net={}", net);
        return net;
    }

    /**
     * Pseudo logic requires:
     *   Cancellation Tax Amount = round2(net * taxRate)
     *   Gross = net + tax
     */
    private BigDecimal addRoundedTax(BigDecimal netSettlement, BigDecimal taxRate) {
        if (netSettlement == null || taxRate == null || taxRate.signum() <= 0) return netSettlement;

        BigDecimal tax = netSettlement.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
        return netSettlement.add(tax);
    }

    private static boolean isNetPremiumLike(Charge c) {
        // NET premium only. Tax is computed separately via taxRate.
        return c.chargeCategory() == ChargeCategory.premium;
    }

    private static BigDecimal sumAmounts(Collection<Charge> charges) {
        if (charges == null || charges.isEmpty()) return BigDecimal.ZERO;
        return charges.stream()
                .map(Charge::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
