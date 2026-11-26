# FCT-004C External Live-Pilot Authorization Form Template

- **Change ID:** `FCT-004C`
- **Milestone:** `FCT-004C`
- **Document ID:** `SPEC-AUTH-FORM-FCT004C`
- **Risk Tier:** `T4 (Critical Risk — Live Network Admission & Credential Entry)`
- **Governance Requirement:** Prior human authorization required before any live cloud connection.

---

## 1. Authorization Scope & Binding Parameters

```yaml
external_pilot_authorization:
  authorization_id: "FCT004C-PILOT-AUTH-001"
  authorized_operator: "Product Owner (Human)"
  governance_approval:
    product_owner_approved: true
    lead_maintainer_approved: true
    approval_date: "2026-11-26"
  target_account_scope:
    authorized_account_identifier: "TEST_ACCOUNT_EMAIL_PLACEHOLDER"
    authorized_region_calling_code: "+1"
  target_robot_scope:
    expected_target_thing_name: "AUTHORIZED_ROBOT_THING_NAME_PLACEHOLDER"
    expected_sub_type: "laser_robot"
  endpoint_trust_scope:
    approved_auth_origin: "https://user.grit-cloud.com"
    approved_auth_path: "/prod/oauth"
    pinned_api_origin: null
    pinned_wss_origin: null
    require_provenance_verification: true
  execution_boundaries:
    max_observation_window_ms: 900000 # 15 minutes
    max_login_attempts: 1
    max_inventory_requests: 1
    physical_commands_permitted: false
    live_map_streaming_permitted: false
  emergency_covenants:
    immediate_disconnect_button_required: true
    flag_secure_enforced: true
    zero_credential_persistence: true
```

---

## 2. Hard-Stop Commitments

1. Under no circumstances may real production credentials or production robot commands be issued without a countersigned instance of this form.
2. In-app execution requires active human confirmation before the admission ticket is dispatched to the network.
