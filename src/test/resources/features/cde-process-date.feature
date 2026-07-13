@cde
Feature: CDE schedules the process date for PASS transactions (R-38, 2nd amendment)

  CDE estimates a process date for every PASS transaction of a DC arrival.
  The header carries the client-supplied collection date; the processing lead
  is applied first (candidate = collection date + lead days), then the final
  adjustment rolls the candidate forward one day at a time past Sundays and
  ZA public holidays. Saturdays are valid process dates. The lead-then-roll
  placeholder stands in for the unrecovered collection-cycle rule (A-3,
  R-35 SYNTHETIC-CONTRACT). CDE schedules, it never emits (R-37).

  Background:
    Given the processing lead is 2 days
    And the ZA public holiday calendar for 2026 is synced

  Scenario: Ratified example - the candidate on a plain weekday is used unchanged
    Given a DC arrival with 2 PASS transactions collected on Monday 2026-07-13
    When the CDE job runs for the arrival
    Then the CDE job completes
    And every scheduled transaction has process date 2026-07-15

  Scenario: A candidate on a Sunday rolls to the Monday
    Given a DC arrival with 2 PASS transactions collected on Friday 2026-02-20
    When the CDE job runs for the arrival
    Then the CDE job completes
    And every scheduled transaction has process date 2026-02-23

  Scenario: A candidate on a holiday Saturday rolls past Sunday and a holiday Monday to Tuesday
    Given 2026-02-14 is a ZA public holiday
    And 2026-02-16 is a ZA public holiday
    And a DC arrival with 3 PASS transactions collected on Thursday 2026-02-12
    When the CDE job runs for the arrival
    Then the CDE job completes
    And every scheduled transaction has process date 2026-02-17

  Scenario: A candidate on a plain Saturday stands as a valid process date
    Given a DC arrival with 2 PASS transactions collected on Thursday 2026-02-19
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
    Given a DC arrival with 4 PASS transactions collected on Monday 2026-07-13
    When the CDE job runs for the arrival
    And the CDE job runs again for the arrival
    Then the CDE job completes
    And exactly 4 transactions are scheduled for the arrival

  Scenario: An arrival with zero PASS transactions completes as a valid no-op
    Given a DC arrival with 0 PASS transactions and 2 failed transactions collected on Monday 2026-07-13
    When the CDE job runs for the arrival
    Then the CDE job completes
    And no transactions are scheduled for the arrival

  Scenario: Every excluded non-PASS verdict is logged as a WARN
    Given a DC arrival with 2 PASS transactions and 3 failed transactions collected on Monday 2026-07-13
    When the CDE job runs for the arrival
    Then the CDE job completes
    And exactly 2 transactions are scheduled for the arrival
    And 3 exclusion warnings are logged for stage CDE with reason CTV_FAIL_ACCOUNT_NOT_FOUND
