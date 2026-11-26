# FCT-004C External Live-Pilot Authorization Form Template

- **Change ID:** `FCT-004C`
- **Milestone:** `FCT-004C`
- **Document ID:** `SPEC-AUTH-FORM-FCT004C`
- **Risk Tier:** `T4 (Critical Risk — Live Network Admission & Credential Entry)`
- **Governance Requirement:** Prior external countersigned human authorization is mandatory before any live cloud connection.

---

## 1. Supervised Trust Properties & Operational Limitations

1. **Separated Provisioning Boundary:** External authorization records are provisioned out-of-band as JSON (`authorized_pilot_record.json` + companion `authorized_pilot_record.sha256`) into app-private `noBackupFilesDir/pilot_external_auth/` and reviewed read-only in the UI. The UI provides zero editable fields for approvers, artifact hashes, account identity, target `thing_name`, endpoints, or budgets.
2. **Integrity & Single-Use Enforcement:** Loading and activating the record verifies strict JSON key uniqueness, zero unknown fields, zero embedded password/token fields, companion `.sha256` match, operator confirmation of the out-of-band 64-hex `record_sha256`, runtime `BuildConfig` identity, installed APK SHA-256 (`applicationInfo.sourceDir`) where supported by the host/device, and a persisted non-credential consumed-record ledger (`consumed_records.txt`) so process restarts and completed pilot runs require a fresh external approval record.
3. **Honest Operational Limitations (No Claimed Asymmetric PKI):** `record_sha256` is an integrity binding against an out-of-band digest, **not** an asymmetric cryptographic signature proving PO/Lead Maintainer identity. The `pilot` buildType is debuggable (`initWith(debug)`) to allow supervised `run-as` provisioning and hardware Keystore instrumentation during bounded operator testing; anyone with ADB/shell access who also controls the operator's out-of-band digest channel could provision a custom record. Host JVM/Robolectric tests without a packaged `.apk` file cannot compute a real `sourceDir` APK digest (`supported = false`), whereas real Android device execution (`TEST-PILOT-469`) hashes the installed APK directly.

---

## 2. First-Live-Attempt Authorization Template (JSON Provisioning Format)

> [!CAUTION]
> - All approval booleans in this template MUST default to `false`.
> - NEVER commit real credentials, production tokens, or real account identifiers into repository files.
> - Passwords MUST NEVER appear in the authorization JSON; any password/secret/token key causes fail-closed rejection.
> - For the initial authentication-only observation (`TEST-PILOT-414`), leave `pinned_api_origin: null`, `pinned_wss_origin: null`, and `endpoint_provenance_ref: null`.

```json
{
  "authorization_id": "FCT004C-PILOT-AUTH-PENDING",
  "authorized_operator": "HUMAN_OPERATOR_PLACEHOLDER",
  "governance_approval": {
    "product_owner_approved": false,
    "approved_by_product_owner": "",
    "lead_maintainer_approved": false,
    "approved_by_lead_maintainer": "",
    "approval_date": "PENDING",
    "external_record_ref": "EXT-AUTH-FCT004C-PENDING"
  },
  "artifact_identity": {
    "candidate_commit_sha": "0000000000000000000000000000000000000000",
    "verified_pilot_apk_sha256": "0000000000000000000000000000000000000000000000000000000000000000",
    "application_id": "com.weback.robot.pilot",
    "version_name": "0.1.0-alpha",
    "version_code": 1
  },
  "target_account_scope": {
    "scope_type": "DEDICATED_ISOLATED_TEST_ACCOUNT",
    "authorized_account_alias": "t***@test.invalid",
    "authorized_account_identifier": "TEST_ACCOUNT_EMAIL_PLACEHOLDER",
    "authorized_region_calling_code": "+1"
  },
  "target_robot_scope": {
    "expected_sub_type_family": "LaserSlam",
    "expected_target_thing_name": "AUTHORIZED_ROBOT_THING_NAME_PLACEHOLDER",
    "redacted_tag": "LASER_TARGET_PLACEHOLDER"
  },
  "endpoint_trust_scope": {
    "approved_auth_origin": "https://user.grit-cloud.com:443",
    "approved_auth_path": "/prod/oauth",
    "pinned_api_origin": null,
    "pinned_wss_origin": null,
    "endpoint_provenance_ref": null,
    "require_provenance_verification": true
  },
  "permitted_operation_set": {
    "permitted_operations": [
      "login",
      "user_thing_list_get"
    ],
    "prohibited_operations": [
      "ws_telemetry_observe",
      "send_to_device"
    ]
  },
  "execution_boundaries": {
    "read_only_mode": true,
    "physical_robot_commands_permitted": false,
    "live_map_streaming_permitted": false,
    "max_login_attempts": 1,
    "max_password_retries": 0,
    "max_captcha_automation": 0,
    "max_inventory_requests": 1,
    "max_initial_ws_connections": 0,
    "max_ws_reconnect_attempts": 0,
    "max_outbound_ws_messages": 0,
    "max_physical_robot_commands": 0,
    "http_call_timeout_ms": 15000,
    "max_observation_window_ms": 900000
  },
  "operating_requirements": {
    "immediate_disconnect_button_required": true,
    "flag_secure_enforced": true,
    "zero_credential_persistence": true,
    "offline_device_checklist_passed": false,
    "physical_device_monotonic_expiry_verified": false
  }
}
```

---

## 3. Hard-Stop Commitments

1. Live networking MUST remain completely inactive unless `product_owner_approved`, `lead_maintainer_approved`, `offline_device_checklist_passed`, and `physical_device_monotonic_expiry_verified` are all `true`, `external_record_ref` is populated, exact candidate/APK digests are externally verified, and the provisioned file SHA-256 matches the out-of-band confirmation digest.
2. Under no circumstances may real credentials, passwords, or real account identifiers be committed to Git, embedded in source code, or substituted with mock authorizations.
3. In-app execution requires active human entry of credentials into the `FLAG_SECURE`-protected **Real-Server Authentication** UI; a UI checkbox alone never grants authorization.
4. Any unapproved dynamic origin returned on login terminates the run after retaining only the sanitized endpoint observation with zero subsequent network requests; any later login requires a fresh external approval record.
