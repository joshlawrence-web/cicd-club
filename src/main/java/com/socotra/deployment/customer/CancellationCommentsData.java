package com.socotra.deployment.customer;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public class CancellationCommentsData {

    private String cancellationReason;
    private Boolean externallyCalculatedSettlementAmount;
    private BigDecimal settlementAmount;
    private BigDecimal taxTotalAmount;
    private BigDecimal grossTotalAmount;
    private BigDecimal settlementLimit;
    private BigDecimal claimCost;
    private BigDecimal defaultCharge;
    private Boolean isPartialRefund;
    private Boolean collectPlanFeeFlag;
    private Boolean refundCheckFlag;
    private Boolean settlementCheckFlag;

    // Getters
    public String getCancellationReason() {
        return cancellationReason;
    }

    public Boolean getExternallyCalculatedSettlementAmount() {
        return externallyCalculatedSettlementAmount;
    }

    public BigDecimal getSettlementAmount() {
        return settlementAmount;
    }

    public BigDecimal getTaxTotalAmount() {
        return taxTotalAmount;
    }

    public BigDecimal getGrossTotalAmount() {
        return grossTotalAmount;
    }

    public BigDecimal getSettlementLimit() {
        return settlementLimit;
    }

    public BigDecimal getClaimCost() {
        return claimCost;
    }

    public BigDecimal getDefaultCharge() {
        return defaultCharge;
    }

    public Boolean getIsPartialRefund() {
        return isPartialRefund;
    }

    public Boolean getCollectPlanFeeFlag() {
        return collectPlanFeeFlag;
    }

    public Boolean getRefundCheckFlag() {
        return refundCheckFlag;
    }

    public Boolean getSettlementCheckFlag() {
        return settlementCheckFlag;
    }

    // Setters
    public void setCancellationReason(String cancellationReason) {
        this.cancellationReason = cancellationReason;
    }

    public void setExternallyCalculatedSettlementAmount(Boolean externallyCalculatedSettlementAmount) {
        this.externallyCalculatedSettlementAmount = externallyCalculatedSettlementAmount;
    }

    public void setSettlementAmount(BigDecimal settlementAmount) {
        this.settlementAmount = settlementAmount;
    }

    public void setTaxTotalAmount(BigDecimal taxTotalAmount) {
        this.taxTotalAmount = taxTotalAmount;
    }

    public void setGrossTotalAmount(BigDecimal grossTotalAmount) {
        this.grossTotalAmount = grossTotalAmount;
    }

    public void setSettlementLimit(BigDecimal settlementLimit) {
        this.settlementLimit = settlementLimit;
    }

    public void setClaimCost(BigDecimal claimCost) {
        this.claimCost = claimCost;
    }

    public void setDefaultCharge(BigDecimal defaultCharge) {
        this.defaultCharge = defaultCharge;
    }

    public void setIsPartialRefund(Boolean isPartialRefund) {
        this.isPartialRefund = isPartialRefund;
    }

    public void setCollectPlanFeeFlag(Boolean collectPlanFeeFlag) {
        this.collectPlanFeeFlag = collectPlanFeeFlag;
    }

    public void setRefundCheckFlag(Boolean refundCheckFlag) {
        this.refundCheckFlag = refundCheckFlag;
    }

    public void setSettlementCheckFlag(Boolean settlementCheckFlag) {
        this.settlementCheckFlag = settlementCheckFlag;
    }
}
