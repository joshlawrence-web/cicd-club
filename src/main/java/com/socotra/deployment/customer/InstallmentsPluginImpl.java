package com.socotra.deployment.customer;

import com.socotra.coremodel.Installment;
import com.socotra.coremodel.InstallmentsPluginRequest;
import com.socotra.coremodel.InstallmentsPluginResponse;
import com.socotra.coremodel.InstallmentUpdate;


import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class InstallmentsPluginImpl implements InstallmentsPlugin {

    @Override
    public InstallmentsPluginResponse updateInstallments(ItemCareRequest request) {
        Map<String, InstallmentUpdate> updates = new LinkedHashMap<>();

        //Installments are a collection/list
        //These setting override the config.json for all installment plans
        for (Installment installment : request.installments()) {

            // 1) Generate at the start of the billing cycle (your “0”)
            Instant generateTime = installment.installmentStartTime();

            // 2) Due at the end of the billing cycle (installmentEndTime)
            Instant dueTime = installment.installmentEndTime();

            InstallmentUpdate update = InstallmentUpdate.builder()
                    .generateTime(generateTime)
                    .dueTime(dueTime)
                    .build();

            updates.put(installment.locator().toString(), update);
        }

        return InstallmentsPluginResponse.builder()
                .installmentUpdates(updates)
                .build();
    }
}