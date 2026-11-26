# FCT-004C External Live-Pilot Authorization Form Template

- **Change ID:** `FCT-004C`
- **Milestone:** `FCT-004C`
- **Document ID:** `SPEC-AUTH-FORM-FCT004C`
- **Risk Tier:** `T4 (Critical Risk — Live Network Admission & Credential Entry)`
- **Governance Requirement:** Prior external countersigned human authorization is mandatory before any live cloud connection.

---

## 1. Authorization Scope & Binding Parameters

> [!CAUTION]
> - All approval booleans in this template MUST default to `false`.
> - NEVER commit real credentials, production tokens, or real account identifiers into repository files.
> - A countersigned human approval record is an administrative governance record under the supervised trust model — it is **NOT** a cryptographically verified digital signature unless cryptographic verification is separately implemented.
> - A UI checkbox alone MUST NOT activate live networking, and `OFFLINE_MOCK_FIXTURE` mock authorizations MUST NEVER authorize live gateways.

```yaml
external_pilot_authorization:
  authorization_id: "FCT004C-PILOT-AUTH-PENDING"
  authorized_operator: "HUMAN_OPERATOR_PLACEHOLDER"
  governance_approval:
    product_owner_approved: false
    approved_by_product_owner: ""
    lead_maintainer_approved: false
    approved_by_lead_maintainer: ""
    approval_date: "PENDING"
    external_record_ref: "EXT-AUTH-FCT004C-PENDING"
  artifact_identity:
    candidate_commit_sha: "0000000000000000000000000000000000000000"
    verified_pilot_apk_sha256: "0000000000000000000000000000000000000000000000000000000000000000"
    application_id: "com.weback.robot.pilot"
    version_name: "0.1.0-alpha"
    version_code: 1
  authorized_physical_device:
    device_model: "AUTHORIZED_DEVICE_MODEL_PLACEHOLDER"
    android_api_level: 31 # Minimum API 31 (Android >= 12)
    device_id_redacted: "DEV_SERIAL_REDACTED_PLACEHOLDER"
    offline_device_checklist_passed: false
  target_account_scope:
    scope_type: "DEDICATED_ISOLATED_TEST_ACCOUNT"
    authorized_account_alias: "t***@test.invalid"
    authorized_account_identifier: "TEST_ACCOUNT_EMAIL_PLACEHOLDER"
    authorized_region_calling_code: "+1"
  target_robot_scope:
    expected_sub_type_family: "LaserSlam"
    expected_target_thing_name: "AUTHORIZED_ROBOT_THING_NAME_PLACEHOLDER"
    redacted_tag: "LASER_TARGET_PLACEHOLDER"
  endpoint_trust_scope:
    approved_auth_origin: "https://user.grit-cloud.com:443"
    approved_auth_path: "/prod/oauth"
    pinned_api_origin: null # Set only after independent provenance verification
    pinned_wss_origin: null # Set only after independent provenance verification
    require_provenance_verification: true
  permitted_operation_set:
    permitted_operations:
      - "login"
      - "user_thing_list_get"
    prohibited_operations:
      - "ws_telemetry_observe"
      - "send_to_device"
  execution_boundaries:
    read_only_mode: true
    physical_robot_commands_permitted: false
    live_map_streaming_permitted: false
    max_login_attempts: 1
    max_password_retries: 0
    max_captcha_automation: 0
    max_inventory_requests: 1
    max_initial_ws_connections: 0
    max_ws_reconnect_attempts: 0
    max_outbound_ws_messages: 0
    max_physical_robot_commands: 0
    http_call_timeout_ms: 15000 # 15 seconds max
    max_observation_window_ms: 900000 # 15 minutes max pilot operating limit
    authorized_at_elapsed_ms: 0
    observation_deadline_elapsed_ms: 0
  operating_requirements:
    immediate_disconnect_button_required: true
    flag_secure_enforced: true
    zero_credential_persistence: true
    physical_device_monotonic_expiry_verified: false
```

---

## 2. Hard-Stop Commitments

1. Live networking MUST remain completely inactive unless all template approval booleans (`product_owner_approved`, `lead_maintainer_approved`) are explicitly approved externally, `external_record_ref` is populated, `offline_device_checklist_passed` and `physical_device_monotonic_expiry_verified` are `true`, and exact candidate/APK digests are externally verified.
2. Under no circumstances may real credentials or real account identifiers be committed to Git, embedded in source code, or substituted with mock authorizations.
3. In-app execution requires active human entry of credentials into the `FLAG_SECURE`-protected UI; a UI checkbox alone never grants authorization.
4. Any unapproved dynamic origin returned on login terminates the run after retaining only the sanitized endpoint observation; any later login requires a separate newly approved authorization.
