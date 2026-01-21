package com.socotra.deployment.customer;

import com.socotra.coremodel.ValidationItem;
import com.socotra.deployment.customer.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public class ItemCareValidationPlugin implements ValidationPlugin {

    @Override
    public ValidationItem validate(ItemCareQuoteRequest request) {
        return validateItemCare(request.quote());
    }

    @Override
    public ValidationItem validate(ItemCareRequest request) {
        if (request.segment().isPresent()) {
            return validateItemCare(request.segment().get());
        }
        return ValidationItem.builder().build();
    }

    private ValidationItem validateItemCare(ItemCare product) {
        List<String> errors = new ArrayList<>();

        // Rule 1: At least one Item (exposure) must exist
        validateExposureExists(product, errors);

        // Rule 2: Premium divisibility by Period of Cover
        validatePremiumDivisibility(product, errors);

        // Rules 3a, 3b: Settlement period validation
        validateSettlementPeriod(product, errors);

        if (errors.isEmpty()) {
            return ValidationItem.builder().build();
        }

        return ValidationItem.builder()
                .elementType("ItemCare")
                .errors(errors)
                .build();
    }

    /**
     * Rule 1: At least one Item exposure must exist on the request
     */
    private void validateExposureExists(ItemCare product, List<String> errors) {
        if (product.items() == null || product.items().isEmpty()) {
            errors.add("At least one Item must exist on the policy");
        }
    }

    /**
     * Rule 2: Premium must divide evenly by Period of Cover to produce a valid currency amount (2 decimal places)
     */
    private void validatePremiumDivisibility(ItemCare product, List<String> errors) {
        Integer periodOfCover = product.data().periodOfCover();
        if (periodOfCover == null || periodOfCover <= 0) {
            return; // Cannot validate if period of cover is not set
        }

        if (product.items() == null || product.items().isEmpty()) {
            return;
        }

        for (Item item : product.items()) {
            BigDecimal premium = findPremiumOnItem(item);
            if (premium != null && premium.compareTo(BigDecimal.ZERO) > 0) {
                // Calculate monthly premium with high precision
                BigDecimal monthlyPremium = premium.divide(BigDecimal.valueOf(periodOfCover), 10, RoundingMode.HALF_UP);
                // Round to 2 decimal places and multiply back
                BigDecimal roundedMonthly = monthlyPremium.setScale(2, RoundingMode.HALF_UP);
                BigDecimal reconstructed = roundedMonthly.multiply(BigDecimal.valueOf(periodOfCover));

                // Check if the reconstructed value matches the original (within tolerance for rounding)
                if (reconstructed.compareTo(premium) != 0) {
                    errors.add("Premium " + premium + " does not divide evenly by Period of Cover (" + periodOfCover + ") to produce a valid monthly amount");
                    break; // Only report once
                }
            }
        }
    }

    /**
     * Rules 3a, 3b: Settlement period validation
     * - 3a: Settlement Period must be >= 1
     * - 3b: Settlement Period Offset in Days must be >= 0
     */
    private void validateSettlementPeriod(ItemCare product, List<String> errors) {
        Integer settlementPeriod = product.data().settlementPeriod();
        Integer settlementPeriodOffsetInDays = product.data().settlementPeriodOffsetInDays();

        // Rule 4a: Settlement Period must be >= 1
        if (settlementPeriod != null && settlementPeriod < 1) {
            errors.add("Settlement Period must be at least 1");
        }

        // Rule 4b: Settlement Period Offset in Days must be >= 0
        if (settlementPeriodOffsetInDays != null && settlementPeriodOffsetInDays < 0) {
            errors.add("Settlement Period Offset in Days must be non-negative");
        }
    }

    /**
     * Helper method to find premium on an item (returns first non-null, non-zero
     * premium)
     */
    private BigDecimal findPremiumOnItem(Item item) {
        if (item.accidentalDamage() != null && item.accidentalDamage().data().premium() != null) {
            return item.accidentalDamage().data().premium();
        }
        if (item.breakdown() != null && item.breakdown().data().premium() != null) {
            return item.breakdown().data().premium();
        }
        if (item.loss() != null && item.loss().data().premium() != null) {
            return item.loss().data().premium();
        }
        if (item.theft() != null && item.theft().data().premium() != null) {
            return item.theft().data().premium();
        }
        if (item.replacement() != null && item.replacement().data().premium() != null) {
            return item.replacement().data().premium();
        }
        if (item.replacementRepair() != null && item.replacementRepair().data().premium() != null) {
            return item.replacementRepair().data().premium();
        }
        if (item.replacementWO() != null && item.replacementWO().data().premium() != null) {
            return item.replacementWO().data().premium();
        }
        if (item.repeatFaultThree() != null && item.repeatFaultThree().data().premium() != null) {
            return item.repeatFaultThree().data().premium();
        }
        if (item.accessories() != null && item.accessories().data().premium() != null) {
            return item.accessories().data().premium();
        }
        return null;
    }
}
