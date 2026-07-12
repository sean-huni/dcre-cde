@cde
Feature: CDE schedules the process date for PASS transactions (R-38)

  CDE estimates a process date for every PASS transaction of a DC arrival:
  the collection date is the business date plus the configured offset, then
  rolled forward one day at a time past Sundays and ZA public holidays.
  Saturdays are valid process dates. CDE schedules, it never emits (R-37).

  Background:
    Given the collection offset is 2 days
    And the ZA public holiday calendar for 2026 is synced

  Scenario: A plain weekday collection date is used unchanged
    Given a DC arrival with 2 PASS transactions collected on Wednesday 2026-02-18
    When the CDE job runs for the arrival
    Then the CDE job completes
    And every scheduled transaction has process date 2026-02-18

  Scenario: A Sunday collection date rolls to the Monday
    Given a DC arrival with 2 PASS transactions collected on Sunday 2026-02-22
    When the CDE job runs for the arrival
    Then the CDE job completes
    And every scheduled transaction has process date 2026-02-23

  Scenario: Ratified example - a holiday Saturday rolls past Sunday and a holiday Monday to Tuesday
    Given 2026-02-14 is a ZA public holiday
    And 2026-02-16 is a ZA public holiday
    And a DC arrival with 3 PASS transactions collected on Saturday 2026-02-14
    When the CDE job runs for the arrival
    Then the CDE job completes
    And every scheduled transaction has process date 2026-02-17

  Scenario: A plain Saturday stands as a valid process date
    Given a DC arrival with 2 PASS transactions collected on Saturday 2026-02-21
    When the CDE job runs for the arrival
    Then the CDE job completes
    And every scheduled transaction has process date 2026-02-21

  Scenario: The job fails closed when the holiday calendar is not synced for the collection year
    Given no ZA public holidays are synced for 2031
    And a DC arrival with 2 PASS transactions collected on 2031-03-05
    When the CDE job runs for the arrival
    Then the CDE job fails
    And no transactions are scheduled for the arrival

  Scenario: Rescheduling the same arrival is idempotent
    Given a DC arrival with 4 PASS transactions collected on Wednesday 2026-02-18
    When the CDE job runs for the arrival
    And the CDE job runs again for the arrival
    Then the CDE job completes
    And exactly 4 transactions are scheduled for the arrival

  Scenario: An arrival with zero PASS transactions completes as a valid no-op
    Given a DC arrival with 0 PASS transactions and 2 failed transactions collected on Wednesday 2026-02-18
    When the CDE job runs for the arrival
    Then the CDE job completes
    And no transactions are scheduled for the arrival

  Scenario: Every excluded non-PASS verdict is logged as a WARN
    Given a DC arrival with 2 PASS transactions and 3 failed transactions collected on Wednesday 2026-02-18
    When the CDE job runs for the arrival
    Then the CDE job completes
    And exactly 2 transactions are scheduled for the arrival
    And 3 exclusion warnings are logged for stage CDE with reason CTV_FAIL_ACCOUNT_NOT_FOUND
