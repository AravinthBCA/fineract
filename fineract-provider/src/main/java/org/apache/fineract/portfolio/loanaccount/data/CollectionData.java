/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.loanaccount.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@AllArgsConstructor
@ToString
@Getter
@Setter
public final class CollectionData {

    private BigDecimal availableDisbursementAmount;
    private Long pastDueDays;
    private LocalDate nextPaymentDueDate;
    private Long delinquentDays;
    private LocalDate delinquentDate;
    private BigDecimal delinquentAmount;
    private LocalDate lastPaymentDate;
    private BigDecimal lastPaymentAmount;

    private LocalDate lastRepaymentDate;
    private BigDecimal lastRepaymentAmount;

    public boolean delinquencyCalculationPaused;
    public LocalDate delinquencyPausePeriodStartDate;
    public LocalDate delinquencyPausePeriodEndDate;
    public Collection<InstallmentLevelDelinquency> installmentLevelDelinquency;

    public static CollectionData template() {
        final BigDecimal zero = BigDecimal.ZERO;
        return new CollectionData(zero, 0L, null, 0L, null, zero, null, zero, null, zero, false, null, null, null);
    }

}
