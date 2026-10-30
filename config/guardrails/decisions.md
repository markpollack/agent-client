# java-guardrails decisions

PROJECT_KIT_VERSION: java-guardrails 0.3.3, globally installed end-user copy; Owner-authorized for the AgentModel compatibility follow-up, keep installed copy fixed and existing gate scope unchanged (2026-10-30)

GATE_ORDER: jacoco, spotbugs, errorprone, pmd, archunit (2026-10-28)

TEST_SUPPORT_MODULES: gate (2026-10-29)

PROJECT_TEST_SUPPORT_REASON: agent-tck is published and supplies provider conformance tests; exercise its abstract TCK classes against a test double inside its own module (2026-10-29)

EXCLUDED_MODULES: agents/hello-world-agent, agents/hello-world-agent-ai (2026-10-29)

PROJECT_EXCLUDED_MODULES_REASON: hello-world-agent and hello-world-agent-ai are sample applications with deployment skipped (2026-10-29)

PROJECT_JACOCO_REMEDIATION: add default-phase own-module tests for agent-models/agent-tck using a test double for its abstract contracts, and unit tests for agent-models/agent-amazon-q, agent-models/agent-amp and agent-client-spring-boot-autoconfigure; these published modules must produce coverage reports before floors are measured (2026-10-29)

JACOCO_FLOORS: agent-client-core 82/51; agent-client-spring-boot-autoconfigure 98/91; agent-launcher 45/46; agent-models/agent-acp 28/29; agent-models/agent-amazon-q 63/69; agent-models/agent-amp 55/69; agent-models/agent-antigravity 50/24; agent-models/agent-claude 42/41; agent-models/agent-codex 52/29; agent-models/agent-gemini 57/42; agent-models/agent-grok 55/38; agent-models/agent-junie 51/54; agent-models/agent-model 58/50; agent-models/agent-qwen-code 74/63; agent-models/agent-swe 56/36; agent-models/agent-tck 79/62; provider-sdks/amazon-q-cli-sdk 41/10; provider-sdks/amp-cli-sdk 10/0; provider-sdks/antigravity-cli-sdk 60/55; provider-sdks/codex-cli-sdk 54/36; provider-sdks/gemini-cli-sdk 32/20; provider-sdks/grok-cli-sdk 60/45; provider-sdks/swe-agent-sdk 58/44 (2026-10-29)

ERRORPRONE_DISABLED: TypeParameterUnusedInFormals (2026-10-29)

PROJECT_ERRORPRONE_DISABLED_REASON: MissingOverride (22): fix. The async work will reshape the session and model interfaces; @Override turns a silently broken override into a compile error. NotJavadoc (12): fix. Move each comment to the element it documents, or make it a plain /* comment. The rest: fix as the kit recommends. ModifyCollectionInEnhancedForLoop, StreamResourceLeak and StringCaseLocaleUsage (use Locale.ROOT) may be real defects, not style. (2026-10-29)

PROJECT_ERRORPRONE_API_EXCEPTION: Temporary, Owner 2026-10-29: the 4 findings are public API (AgentResponseMetadata.get/getRequired, AgentClientResponse.getPhaseCapture, the setup-context accessors) decided in the async design (ASYNC-4/5). Re-enable in the async implementation; ASYNC-5 is not done while this check is off.

PMD_MECHANISM: ratchet (2026-10-29)

PROJECT_PMD_RATCHET_REASON: Owner 2026-10-29: agent-claude's execution-path violations are left to ASYNC-5, which rebuilds that path; its ratchet must reach 0 there.

PROJECT_PMD_SCOPE: Fix every PMD violation outside agent-models/agent-claude to zero. In agent-claude, fix option-mapping hotspots in ClaudeAgentModel.buildCLIOptions, ClaudeAgentProperties, ClaudeAgentOptions, ClaudeAgentAutoConfiguration and ClaudeHookBeanPostProcessor. Retain only execution-path hotspots in ClaudeAgentModel.call/iterate/stream and its class size, ClaudeAgentSession and ClaudeAgentSessionRegistry; set the module ratchet to the measured remainder. CPD stays at zero. (2026-10-29)

ARCHUNIT_CYCLES: scope agent-models/agent-amazon-q, agent-models/agent-amp, agent-models/agent-antigravity, agent-models/agent-claude, agent-models/agent-codex, agent-models/agent-gemini, agent-models/agent-grok, agent-models/agent-junie, agent-models/agent-qwen-code, provider-sdks/amazon-q-cli-sdk, provider-sdks/amp-cli-sdk, provider-sdks/antigravity-cli-sdk, provider-sdks/codex-cli-sdk, provider-sdks/grok-cli-sdk (2026-10-30)

PROJECT_ARCHUNIT_SCOPE_REASON: Owner 2026-10-30: cycles in agent-client-core and agent-model are left to the async design; the SDK package cycles are deferred because breaages.

PROJECT_ARCHUNIT_SDK_REASON: The preceding Owner quotation is preserved as received; the complete preceding instruction defers provider-sdks/gemini-cli-sdk and provider-sdks/swe-agent-sdk because breaking their cycles moves public types between packages, a breaking change outside this pass. (2026-10-30)

PROJECT_ARCHUNIT_SINGLE_PACKAGE_DEFERRED: agent-client-spring-boot-autoconfigure, agent-launcher, agent-models/agent-acp, agent-models/agent-swe, agent-models/agent-tck; kit 0.3.2 cannot evaluate its package rule for these single-package modules. Leave them outside the ArchUnit gate pending the kit fix; do not install a failOnEmptyShould override. (2026-10-30)

PROJECT_ARCHUNIT_INTRODUCED_CYCLE: ClaudeAgentProperties and ClaudePropertyOptionsMapper: introduced by this pass, fixed in 09c322e before gate installation; no public or protected signature changed. (2026-10-30)

PROJECT_AGENTMODEL_COMPATIBILITY: Owner 2026-10-30: restore the AgentModel provider supertypes, bean return types, published TCK field and Grok ACP wrapper from 2655def. Defer the AgentApi migration to its own release after async. Keep the accepted EnvironmentPostProcessor package/key move and other changes from d8fcd10.

PROJECT_AGENTMODEL_REMOVAL_SUPPRESSIONS: Owner-authorized exception to the kit source-suppression rule: narrow @SuppressWarnings("removal") on 10 provider classes, 6 bean methods and the published TCK field, plus its in-module test double (17 production, 1 test). Reason at each site: AgentModel kept for compatibility until the AgentApi migration release. Compiler removal warnings remain enabled and unsuppressed new uses must fail. (2026-10-30)

PROJECT_ARCHUNIT_FOLLOWUP_SCOPE: Owner 2026-10-30: adopting installed kit 0.3.3 for this follow-up does not widen the ArchUnit gate; the five single-package modules listed above remain outside it.
