*** Begin Patch
*** Add File: APPLIED_CHANGES.md
+I applied fixes to make USSD advanced-session processing wait for the next popup before entering/sending the next value and to prevent the accessibility service being disabled by the automation flow.
+
+Files changed:
+- app/src/main/java/com/bingwa/mobile/UssdNavigationService.kt
+  - Strengthened pending step advance gating (buildStepAdvanceSignatureKey, startPendingStepAdvance, handlePendingStepAdvance)
+  - Ensured input writes use ACTION_SET_TEXT with clipboard fallback and robust verification
+  - Added defensive recycling and timeout handling
+- app/src/main/java/com/bingwa/mobile/UssdQueue.kt
+  - Ensured USSD tasks run sequentially and do not overlap
+- app/src/main/java/com/bingwa/mobile/AutomationService.kt
+  - Avoided premature stopSelf calls that could interfere with accessibility flows
+
+I pushed these changes to the main branch as requested and created a draft release tag (v1.0.1) without an APK attached.
+
+Triggering GitHub Actions release build for v3.5.17.
+
+Testing recommendations:
+- Manually test multi-step USSD flows on representative devices (old and new Android versions).
+- Tune timeouts in UssdNavigationService constants for slow OEM dialers if needed.
+
*** End Patch