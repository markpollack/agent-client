# java-guardrails decisions

PROJECT_KIT_VERSION: java-guardrails 0.3.2, installed with npx skills add markpollack/java-guardrails; Owner-authorized update, keep installed copy fixed for this pass (2026-10-29)

GATE_ORDER: jacoco, spotbugs, errorprone, pmd, archunit (2026-10-28)

TEST_SUPPORT_MODULES: gate (2026-10-29)

PROJECT_TEST_SUPPORT_REASON: agent-tck is published and supplies provider conformance tests; exercise its abstract TCK classes against a test double inside its own module (2026-10-29)

EXCLUDED_MODULES: agents/hello-world-agent, agents/hello-world-agent-ai (2026-10-29)

PROJECT_EXCLUDED_MODULES_REASON: hello-world-agent and hello-world-agent-ai are sample applications with deployment skipped (2026-10-29)

PROJECT_JACOCO_REMEDIATION: add default-phase own-module tests for agent-models/agent-tck using a test double for its abstract contracts, and unit tests for agent-models/agent-amazon-q, agent-models/agent-amp and agent-client-spring-boot-autoconfigure; these published modules must produce coverage reports before floors are measured (2026-10-29)

JACOCO_FLOORS: agent-client-core 82/51; agent-client-spring-boot-autoconfigure 98/91; agent-launcher 45/46; agent-models/agent-acp 28/29; agent-models/agent-amazon-q 63/69; agent-models/agent-amp 55/69; agent-models/agent-antigravity 50/24; agent-models/agent-claude 42/41; agent-models/agent-codex 52/29; agent-models/agent-gemini 57/42; agent-models/agent-grok 55/38; agent-models/agent-junie 51/54; agent-models/agent-model 58/50; agent-models/agent-qwen-code 74/63; agent-models/agent-swe 56/36; agent-models/agent-tck 79/62; provider-sdks/amazon-q-cli-sdk 41/10; provider-sdks/amp-cli-sdk 10/0; provider-sdks/antigravity-cli-sdk 60/55; provider-sdks/codex-cli-sdk 54/36; provider-sdks/gemini-cli-sdk 32/20; provider-sdks/grok-cli-sdk 60/45; provider-sdks/swe-agent-sdk 58/44 (2026-10-29)

ERRORPRONE_DISABLED: none

PROJECT_ERRORPRONE_DISABLED_REASON: MissingOverride (22): fix. The async work will reshape the session and model interfaces; @Override turns a silently broken override into a compile error. NotJavadoc (12): fix. Move each comment to the element it documents, or make it a plain /* comment. The rest: fix as the kit recommends. ModifyCollectionInEnhancedForLoop, StreamResourceLeak and StringCaseLocaleUsage (use Locale.ROOT) may be real defects, not style. (2026-10-29)
