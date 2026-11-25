package com.weback.robot.domain.device

import com.weback.robot.domain.model.ControlCompatibility
import com.weback.robot.domain.model.DeviceCapability
import com.weback.robot.domain.model.DeviceCapabilityCategory

/**
 * Pure Kotlin classifier normalizing `sub_type` per RE baseline `utils/c.java:1131-1142` (`c2`)
 * and mapping hardware families grounded in `enums/p.java` and `utils/c.java`.
 */
object DeviceCapabilityClassifier {

    // RE source: utils/c.java:1741-1743 (n1 — 5 ThroughTek camera prefixes)
    private val throughtekCameraPrefixes = listOf(
        "kt-589-", "xs-x5c2-", "xs-x6pro-", "xs-x5c-", "xs-v20pro-"
    )

    // RE source: utils/c.java (m1=8, N0=2, Y0=7, v0=7, g1=1 — excluded smart-home peripherals)
    private val peripheralPrefixes = listOf(
        "thermostat-br-", "by-t", "thermostat-by-", "th12-001-",
        "ck-001-", "ck-002-", "ck-002s-", "ck-005-",
        "lamp-br-", "lamp-gd5-",
        "sock-br-", "sock-c85-", "sock-pw-", "plug01-br-", "plug02-br-", "switch-2ws-", "switch-3ws-",
        "ziglt-zp11-", "tendm-th35-", "an-h35s-", "tendm-tp11-", "tendm-tp12s-", "tendm-tp11s-", "an-a10s-",
        "tx3-mini-"
    )

    // RE source: utils/c.java (H0=4, U0=10 — legacy XMPP / older hardware)
    private val legacyXmppPrefixes = listOf(
        "2016", "yb-r162-", "yb-rav00-", "yb-r163-",
        "wotn-y320b-", "hxs-c1-", "hxs-c2-", "hxs-c3-",
        "x-mamoru-", "wotn-c340-", "jl-sra07-", "fr-t02-"
    )

    // RE source: utils/c.java:584-586 (P0 — 64 Laser SLAM / map-capable prefixes)
    private val laserSlamPrefixes = listOf(
        "yg-yw10001-", "xs-x8-", "xs-x8test-", "iqlean-lr01-", "xs-x8blts-", "xs-r20s-",
        "rvc66-pro-", "xs-r30-", "x5ep-ezicom-", "rapido-r7s-", "tw-r30-", "rapido-r8s-",
        "kt-577-", "xs-x8pro-", "rapido-rr8-", "aswing-x8uv-", "rj-xonew-", "rj-xoneb-",
        "tab-ts60h-", "exvac-680s-", "hxchip-t800-", "tesvor-t8-", "yg-bvls-", "yg-bvts-",
        "okami-u100-", "hx-x700-", "hx-x580-", "bot-xpro-", "43657321-542-", "rv-w75019-",
        "hx-x580t-", "nsvor-x600pro-", "yg-bvts2900-", "yg-bvts1500-", "exvac-890-",
        "hx-x700t-", "jq-36u1-", "hx-x580s-", "neatsvor-x600-", "libos-lbs20-", "webber-x580-",
        "hx-x700s-", "p-laserbot-", "simum-6-", "exvac-880s-", "exvac-880-", "hx-t800s-",
        "hx-t800t-", "hm800-robot-", "exvac-s680s-", "kurumi-kv03-", "kv03-laser-",
        "rapdio-rr8-", "tesvor-s6-", "tesvor-s7-", "s6-turbo-", "xs-r20-", "neatsvor-s600-",
        "prologic-t800-", "prologic-h510-", "hiking-x8pro-", "exvac895-xtc-", "x-stylew-", "x-styleb-"
    )

    // RE source: utils/c.java:2046-2048 (q1 — 43 visual/gyro-mapped prefixes, disjoint from P0)
    private val visualGyroPrefixes = listOf(
        "yw-vs01-", "yg-vs-", "bl-vs-", "bn-01w-", "hx-t810-", "cr-01w-", "bn-02w-",
        "bl-05w-", "bl-08w-", "sysperl-v50-", "chigo-760-", "jn-a3-", "xs-x6-",
        "ku-ppr8781-", "iqlean-cr02-", "xs-v20s-", "iqlean-cr01-", "garlyn-600-",
        "ze-zrvision-", "rapido-r6s-", "uv-srv20-", "xs-v20-", "rapido-rr6-", "suzuka-cyc-",
        "rj-focusw-", "iboto-c820w-", "xs-x62-", "psnc-vs-", "bl-n0w-", "chigo-s022-",
        "sdg-s023-", "ly-vs-", "klsm-k186-", "clpanda-i7-", "suhoo-t8-", "sv-8020-",
        "yg-vsobs-", "okami-u90-", "rv-w65019-", "genio-n600-", "xs-x3-", "hns-i7-", "xs0-v20-"
    )

    // RE source: utils/c.java:1535-1537 & 522-524 (l1 / L0 minus peripherals/q1 -> non-laser gyro vacuums)
    private val gyroVacuumPrefixes = listOf(
        "yb-rwi00-", "hxs-t370-", "hxs-g1-", "wotn-j340p-", "hxs-t360-", "sw-r9-", "bl-03-",
        "cd-20-", "airob-m178-", "hrl-b7-", "wotn-h330-", "erc-284-", "nv-01w-", "gv-350r-",
        "wotn-h320-", "zs-m1-", "tab-qt550-", "tab-qt560-", "tab-qt710-", "zoho-q6-",
        "vlgo-m168-", "dihui-m188-", "aige-m198-", "hb-j320-", "tab-t360-", "tab-qt360w-",
        "drobot-d400-", "ty-x500-", "ty-x500blts-", "tesvor-x500a-", "robot-m-", "exvac-660-",
        "hg-x500-", "pureatic-v5-", "xjh-009c-", "clymen-q8-", "clymen-q9-", "hx-x505-",
        "hg-t500-", "sp-8000-", "sw-m9-", "as-lx168-", "iplus-x700-", "fr-m1-", "romass-x500-",
        "ls-d600-", "amarey-a900-", "zk-803-", "hx-x506-", "rob-vacuum8-", "dealdig-rob-",
        "mamibot-280-", "hx-k100s-", "valubot-k100-", "pucrc-660-", "sw-r73-", "jl-jora08-",
        "zeglint-d5-", "ebn-e8-", "clymen-q8m-", "clymen-q9m-", "hnc-m8-", "sysperl-v30-",
        "bl-01w-", "hns-i6-", "chigo-017-", "sdg-012-", "bl-02w-", "e8-plus-", "ssm-b33-",
        "yf-810-", "concept-3000-", "bv-08s-", "vens-m-", "rj-duel3-", "b-x5-", "x500-plus-",
        "pucrc660-s-", "xs-x5-", "xs-x5blts-", "alm-rv1702y-", "xs-g20-", "iqlean-gr01-",
        "x420gw-aqua-", "x425gwe-aqua-", "ze-zrnext-", "xs-g20s-", "xs-r31-", "7neebo-grey-",
        "xs-x51-", "gyro-r300-", "tw-r15-", "rvc56-pro-", "ku-ppr2709-", "ku-ppr6612-",
        "uv-su120-", "ubot-660-", "xrobot-i-", "xrobot-t-", "ty-v300s-", "ac-plus-", "mt-930-",
        "mt-910a-", "orfeld-x500-", "orfeld-x503-", "zw-s6-", "zs-s8-", "bv-03s-",
        "exvac-st660s-", "suzuka-pro-", "yubot-m1-", "annew-a2max-", "ty-x500pro-",
        "ty-x500pro2-", "tesvor-x500pa-", "iks-c24784-", "770-robot-", "neastvor-x5-",
        "exvac-st660-", "iks-c24514-", "suhoo-t5-", "deenkee-700-", "prevac-650-",
        "neatsvor-v3-", "mamibot-280p-", "okami-u80-", "ebn-a9-", "petvac-360-", "inse-e5-",
        "bl-03w-", "xform-bl03w-", "neatsvor-x5-", "rs-x500-", "tesvor-v302-", "lrsx-01-",
        "hx-x500t-", "hx-x500s-", "pucrc-675-", "gadnic-z970-", "ropo-glass3-", "merc-kx500w-",
        "tesvor-m1-", "tesvor-m1a-", "p-cleanbot-", "alfawise-v8s-", "concept-code-",
        "tm-sav48ds-", "neatsvor-x500-", "stech-rb001-", "neatsvor-x520-", "hx-x500ta-",
        "schbot-sts8s-", "qole-q5-"
    )

    fun classify(subType: String?): DeviceCapability {
        val trimmed = subType?.trim()?.lowercase().orEmpty()
        if (trimmed.isEmpty()) {
            return DeviceCapability(
                normalizedSubType = "",
                category = DeviceCapabilityCategory.UNKNOWN,
                compatibility = ControlCompatibility.UnsupportedUnknown,
                unsupportedReason = "Empty device sub_type"
            )
        }
        val normalized = if (trimmed.endsWith("-")) trimmed else "$trimmed-"

        return when {
            throughtekCameraPrefixes.any { normalized.startsWith(it) } -> DeviceCapability(
                normalizedSubType = normalized,
                category = DeviceCapabilityCategory.THROUGHTEK_CAMERA_VACUUM,
                compatibility = ControlCompatibility.UnsupportedCamera,
                unsupportedReason = "ThroughTek camera hardware excluded from FCT-003"
            )
            peripheralPrefixes.any { normalized.startsWith(it) } -> DeviceCapability(
                normalizedSubType = normalized,
                category = DeviceCapabilityCategory.PERIPHERAL,
                compatibility = ControlCompatibility.UnsupportedPeripheral,
                unsupportedReason = "Smart-home peripheral excluded from vacuum control"
            )
            legacyXmppPrefixes.any { normalized.startsWith(it) } -> DeviceCapability(
                normalizedSubType = normalized,
                category = DeviceCapabilityCategory.LEGACY_XMPP,
                compatibility = ControlCompatibility.UnsupportedLegacyXmpp,
                unsupportedReason = "Legacy XMPP device excluded from MVP"
            )
            laserSlamPrefixes.any { normalized.startsWith(it) } -> DeviceCapability(
                normalizedSubType = normalized,
                category = DeviceCapabilityCategory.LASER_SLAM,
                compatibility = ControlCompatibility.MvpSupportedShadowWs,
                unsupportedReason = null
            )
            visualGyroPrefixes.any { normalized.startsWith(it) } -> DeviceCapability(
                normalizedSubType = normalized,
                category = DeviceCapabilityCategory.VISUAL_GYRO_MAPPED,
                compatibility = ControlCompatibility.UnsupportedNonLaserVacuum,
                unsupportedReason = "Visual/gyro-mapped model outside MVP Laser SLAM scope"
            )
            gyroVacuumPrefixes.any { normalized.startsWith(it) } -> DeviceCapability(
                normalizedSubType = normalized,
                category = DeviceCapabilityCategory.GYRO_VACUUM,
                compatibility = ControlCompatibility.UnsupportedNonLaserVacuum,
                unsupportedReason = "Non-laser gyro vacuum excluded from MVP"
            )
            else -> DeviceCapability(
                normalizedSubType = normalized,
                category = DeviceCapabilityCategory.UNKNOWN,
                compatibility = ControlCompatibility.UnsupportedUnknown,
                unsupportedReason = "Unrecognized sub_type prefix"
            )
        }
    }
}
