# Round 9: Complete pspanid Cross-Instance Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** End-to-end fix for the cross-instance `pspanid` MDC key mismatch — `LogAspect` correctly derives `pspanid` from the upstream `traceparent` header's `parent-id`, AND `SimpleMonitorServiceImpl` persists cross-instance inbound traces (with upstream parent outside our JVM) as top-level entries so `/view/list` and `/view/traceid?id=` can find them.

**Architecture:** Two coordinated changes — (1) `LogAspect` learns to read `LOG_PSPAN_ID` (set by `W3CTraceContextPropagator.extract`) when `LOG_SPAN_ID` is absent (the cross-instance signal), falling back to `LOG_SPAN_ID` for in-process nested calls. (2) `SimpleMonitorServiceImpl` switches from "save if `pspanid == null`" to "save if no in-process parent in `methodTraceInfoMap`" — this captures both true roots AND cross-instance inbounds while leaving in-process children unattached-to-store as before. `TracePropagationIT` and `OtelPropagationIT` flip their KNOWN-GAP assertions to encode the post-fix contract.

**Tech Stack:** Java 17, Spring Boot 3.5, Maven multi-module. No new dependencies.

**Spec:** `.superpowers/sdd/2026-08-29-full-coverage-e2e-plan/progress.md` (Ruling 6 — Round 8 deferral rationale + Round 9 resolution).

**Background context (from Round 8 diagnostic):**

- `W3CTraceContextPropagator.extract` writes `MDC_TRACE_ID` + `MDC_PSAN_ID` + `MDC_SAMPLED` (NOT `MDC_SPAN_ID` — the inbound side has no local span yet). See `methodTraceLog/src/main/java/cn/wubo/method/trace/log/context/W3CTraceContextPropagator.java:96`.
- `LogAspect.java:154` reads `prespanid = MDC.get(LOG_SPAN_ID)` and line 168 assigns `pspanid = prespanid` — null on cross-instance inbound.
- `SimpleMonitorServiceImpl.java:77-79` saves to trace store only when `pspanid == null`. The cross-instance inbound (after fix) has `pspanid != null` AND its parent is in a different JVM (not in `methodTraceInfoMap`), so the trace disappears from `/view/list`.

## Global Constraints

- `mvn install -DskipTests -Dgpg.skip=true` must BUILD SUCCESS after every starter/autoconfigure change.
- `mvn -pl methodTraceLog-test test -Dtest='cn.wubo.method.trace.log.e2e.*IT' -Dgpg.skip=true` must pass green.
- 214 existing tests must continue to pass (188 unit + 26 e2e + 1 OTel skip).
- No new dependencies; reuse existing deps.
- All 5 modified files committed in a single Round 9 commit (sequential changes form one logical fix; do not split into separate commits).
- LogAspect.mdc finally block must remain null-safe (inbound path passes `prespanid == null`; MDC.put with null throws).

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `methodTraceLog/src/main/java/cn/wubo/method/trace/log/LogAspect.java` | Modify line 168 | pspanid derivation (1-line change + comment) |
| `methodTraceLog/src/main/java/cn/wubo/method/trace/log/impl/monitor/SimpleMonitorServiceImpl.java` | Modify lines 77-85 (BEFORE branch) and 99-105 (AFTER branch) | "Save to store" condition (2-line change + comment) |
| `methodTraceLog-test/src/test/java/cn/wubo/method/trace/log/e2e/OtelPropagationIT.java` | Modify lines 198-209 | KNOWN GAP assertion flip |
| `methodTraceLog-test/src/test/java/cn/wubo/method/trace/log/e2e/TracePropagationIT.java` | Verify (likely no change) | Confirm cross-instance inbound now appears in `/view/list` |
| `CLAUDE.md` | Modify line 168 (CorsFilterConfig) and any future stale claims | Doc cleanup if needed |
| `TEST_REPORT.md` | Append round-9 section | Report |

---

### Task 1: Fix LogAspect pspanid derivation

**Files:**
- Modify: `methodTraceLog/src/main/java/cn/wubo/method/trace/log/LogAspect.java:168`

**Interfaces:**
- Consumes: `prepspanid = MDC.get(LOG_PSPAN_ID)`, `prespanid = MDC.get(LOG_SPAN_ID)` (lines 153-154 — unchanged)
- Produces: `pspanid` variable (line 156/168) — assigned correctly for both in-process nested AND cross-instance inbound

- [ ] **Step 1: Apply the fix**

In `LogAspect.java`, replace line 167-172:
```java
        // 若无跟踪ID，则生成一个新的；否则获取当前跨度ID作为父跨度ID
        if (traceid == null) {
            traceid = UUID.randomUUID().toString();
        } else {
            pspanid = prespanid;
        }
```
with:
```java
        // 若无跟踪ID，则生成一个新的；否则获取当前跨度ID作为父跨度ID。
        // Round 9 修复：区分两种"继承 trace"场景：
        //   1. 进程内嵌套调用：上层 LogAspect 已设 LOG_TRACE_ID + LOG_SPAN_ID，prespanid 非 null
        //      → pspanid = prespanid（外层 spanid）
        //   2. 跨实例 inbound：W3CTraceContextPropagator 只设了 LOG_TRACE_ID + LOG_PSAN_ID（来自
        //      traceparent 的 parent-id），LOG_SPAN_ID 是 null（inbound 还没本地 span）
        //      → pspanid = prepspanid（上游 parent spanid）
        // 区分信号：prespanid != null → 进程内；prespanid == null → 跨实例。
        if (traceid == null) {
            traceid = UUID.randomUUID().toString();
        } else {
            pspanid = prespanid != null ? prespanid : prepspanid;
        }
```

- [ ] **Step 2: Verify the fix compiles**

Run: `/c/developer/apache-maven-3.9.16/bin/mvn -pl methodTraceLog -am compile -Dgpg.skip=true`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Reinstall**

Run: `/c/developer/apache-maven-3.9.16/bin/mvn -pl methodTraceLog install -DskipTests -Dgpg.skip=true`
Expected: BUILD SUCCESS.

(Steps 2-3 verify the fix doesn't break compilation. Per Global Constraints, the final commit happens in Task 5 after all 5 changes are staged. Don't commit yet.)

---

### Task 2: Update SimpleMonitorServiceImpl save condition

**Files:**
- Modify: `methodTraceLog/src/main/java/cn/wubo/method/trace/log/impl/monitor/SimpleMonitorServiceImpl.java:77-85` (BEFORE branch)
- Modify: `methodTraceLog/src/main/java/cn/wubo/method/trace/log/impl/monitor/SimpleMonitorServiceImpl.java:99-105` (AFTER branch)

**Interfaces:**
- Consumes: `serviceCallInfo.getPspanid()` — now non-null for cross-instance inbound after Task 1
- Consumes: `methodTraceInfoMap` — concurrent map of in-process active spans by spanid
- Produces: `traceStore.save(methodTraceInfo)` — should fire for BOTH true roots AND cross-instance inbounds (where parent is in another JVM)

- [ ] **Step 1: Update BEFORE branch**

In `SimpleMonitorServiceImpl.java`, replace lines 75-85:
```java
            MethodTraceInfo methodTraceInfo = MethodTraceInfo.create(serviceCallInfo);
            methodTraceInfoMap.put(serviceCallInfo.getSpanid(), methodTraceInfo);
            if (serviceCallInfo.getPspanid() == null) {
                // 根节点：先在 store 中占位（BEFORE 阶段，让列表立即可见）
                traceStore.save(methodTraceInfo);
            } else {
                MethodTraceInfo parent = methodTraceInfoMap.get(serviceCallInfo.getPspanid());
                if (parent != null) {
                    parent.addChild(methodTraceInfo);
                }
            }
```
with:
```java
            MethodTraceInfo methodTraceInfo = MethodTraceInfo.create(serviceCallInfo);
            methodTraceInfoMap.put(serviceCallInfo.getSpanid(), methodTraceInfo);
            // Round 9: 改为"无 in-process parent"判断。
            //   - pspanid == null（真根）→ parent 找不到 → save
            //   - pspanid != null 但 parent 不在 methodTraceInfoMap（跨实例 inbound，
            //     parent 在另一个 JVM 里）→ save as root
            //   - pspanid != null 且 parent 在 map 里（进程内嵌套）→ 挂为子节点，不 save
            MethodTraceInfo parent = serviceCallInfo.getPspanid() == null
                    ? null
                    : methodTraceInfoMap.get(serviceCallInfo.getPspanid());
            if (parent != null) {
                parent.addChild(methodTraceInfo);
            } else {
                // 根节点（含跨实例 inbound）：先在 store 中占位（BEFORE 阶段，让列表立即可见）
                traceStore.save(methodTraceInfo);
            }
```

- [ ] **Step 2: Update AFTER branch**

In `SimpleMonitorServiceImpl.java`, replace lines 99-105:
```java
            MethodTraceInfo methodTraceInfo = methodTraceInfoMap.remove(serviceCallInfo.getSpanid());
            if (methodTraceInfo != null) {
                methodTraceInfo.end(serviceCallInfo);
                // 根节点再次写入 store，让 store 看到 after 字段
                if (methodTraceInfo.getBefore() != null && methodTraceInfo.getBefore().getPspanid() == null) {
                    traceStore.save(methodTraceInfo);
                }
            }
```
with:
```java
            MethodTraceInfo methodTraceInfo = methodTraceInfoMap.remove(serviceCallInfo.getSpanid());
            if (methodTraceInfo != null) {
                methodTraceInfo.end(serviceCallInfo);
                // Round 9: 与 BEFORE 分支一致的"无 in-process parent"判断
                //   - 真根 → save（让 store 看到 after 字段）
                //   - 跨实例 inbound → save（同上）
                //   - 进程内嵌套 → 不 save（children 不进 store，只挂在 parent 下）
                if (methodTraceInfo.getBefore() != null
                        && methodTraceInfoMap.get(methodTraceInfo.getBefore().getPspanid()) == null) {
                    traceStore.save(methodTraceInfo);
                }
            }
```

- [ ] **Step 3: Verify compile**

Run: `/c/developer/apache-maven-3.9.16/bin/mvn -pl methodTraceLog -am compile -Dgpg.skip=true`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Reinstall**

Run: `/c/developer/apache-maven-3.9.16/bin/mvn -pl methodTraceLog install -DskipTests -Dgpg.skip=true`
Expected: BUILD SUCCESS.

---

### Task 3: Flip OtelPropagationIT KNOWN GAP assertion

**Files:**
- Modify: `methodTraceLog-test/src/test/java/cn/wubo/method/trace/log/e2e/OtelPropagationIT.java:198-209`

**Interfaces:**
- Consumes: After Task 1+2, primary's inbound `aspectLogDemo` trace has `pspanid = wire parent-id` (non-null), appears in `/view/list`
- Produces: Post-fix assertion contract

- [ ] **Step 1: Replace the KNOWN GAP block**

In `OtelPropagationIT.java`, replace lines 196-209 (the block starting with the PRODUCT GAP comment through `isNull()`):
```java
        // PRODUCT GAP (Ruling 6): TraceContextFilter writes upstream parent-id to
        // MDC key 'pspanid', but LogAspect.around reads it from MDC key 'spanid' —
        // which the filter never sets. Result: primary's inbound root has pspanid==null.
        // Round 8 reverted the proposed fix because it broke SimpleMonitorServiceImpl's
        // "pspanid==null means root" simplification (cross-instance inbound traces
        // no longer appeared in /view/list). Full fix needs architectural changes —
        // deferred to a future round; see Round 8 decision.
        ServiceCallInfo beforeOnPrimary = primaryInbound.get().getBefore();
        assertThat(beforeOnPrimary.getPspanid())
                .as("KNOWN GAP from Task 3 review (Ruling 6) + Round 8 deferral: "
                        + "cross-instance parent/child linking via pspanid is NOT wired end-to-end. "
                        + "LogAspect.java:162 reads prespanid from MDC key 'spanid'; "
                        + "W3CTraceContextPropagator writes to MDC key 'pspanid'. "
                        + "Traceid propagation IS wired — see the assertThat above this one.")
                .isNull();
```
with:
```java
        // Round 9: LogAspect pspanid fix is wired. Cross-instance inbound now carries
        // the upstream parent's span id (from traceparent header, written by
        // W3CTraceContextPropagator to MDC.LOG_PSAN_ID). SimpleMonitorServiceImpl
        // saves cross-instance traces as top-level entries (no in-process parent
        // in methodTraceInfoMap), so /view/list returns them and getByTraceid works.
        ServiceCallInfo beforeOnPrimary = primaryInbound.get().getBefore();
        assertThat(beforeOnPrimary.getPspanid())
                .as("post-Round-9 fix: cross-instance pspanid should be wired from "
                        + "MDC.LOG_PSAN_ID; if this fails, LogAspect.java:168 likely regressed "
                        + "to prespanid-only reading")
                .isNotNull();
```

- [ ] **Step 2: Verify OtelPropagationIT still skips**

Run: `/c/developer/apache-maven-3.9.16/bin/mvn -pl methodTraceLog-test test -Dtest=OtelPropagationIT -Dgpg.skip=true`
Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 1` (skip happens because OTel SDK isn't loaded; the assertion flip only matters when OTel is enabled).

- [ ] **Step 3: Verify TracePropagationIT still passes (regression check)**

Run: `/c/developer/apache-maven-3.9.16/bin/mvn -pl methodTraceLog-test test -Dtest=TracePropagationIT -Dgpg.skip=true`
Expected: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`. The cross-instance inbound trace now appears in `/view/list` (because Task 2 saves it as a top-level entry), so the test's `findFirst()` filter finds it.

---

### Task 4: Update CLAUDE.md (remove Round 8 deferral note)

**Files:**
- Modify: `CLAUDE.md` (search for any "Ruling 6" or "Round 8 deferral" or "pspanid" references)

**Interfaces:**
- Consumes: Current CLAUDE.md
- Produces: CLAUDE.md without deferral language for pspanid bug

- [ ] **Step 1: Search for deferral language**

Run (via Grep):
```
grep -nE "Ruling 6|Round 8|pspanid.*not.*wired|KNOWN GAP" CLAUDE.md
```

- [ ] **Step 2: Remove or update deferral language**

If any matches found, replace with the post-fix narrative:
```markdown
  - `LogAspect.around` correctly derives `pspanid` for both in-process nested calls (from `MDC.LOG_SPAN_ID`) and cross-instance inbound (from `MDC.LOG_PSAN_ID` via `W3CTraceContextPropagator`). `SimpleMonitorServiceImpl` saves cross-instance traces as top-level store entries since they have no in-process parent.
```

If no matches, skip this task (no doc update needed).

- [ ] **Step 3: Verify CLAUDE.md still readable**

Run: `head -20 CLAUDE.md` (sanity check the file structure is intact).

---

### Task 5: Run full test suite + commit

**Files:**
- No file changes (just verification + commit)

**Interfaces:**
- Consumes: All 4 changes from Tasks 1-4
- Produces: A single Round 9 commit + verified 214 tests passing

- [ ] **Step 1: Clean any leftover test processes**

Run (via PowerShell):
```powershell
powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 8085,8086 -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id \$_.OwningProcess -Force -ErrorAction SilentlyContinue }; 'cleaned'"
```
Expected: "cleaned".

- [ ] **Step 2: Run full test suite**

Run: `/c/developer/apache-maven-3.9.16/bin/mvn -pl methodTraceLog-test test -Dgpg.skip=true`
Expected: `Tests run: 214, Failures: 0, Errors: 0, Skipped: 1`. (BUILD SUCCESS)

If any IT fails, STOP — diagnose before committing. Common failure modes:
- `TracePropagationIT` fails → LogAspect fix didn't take effect, or Task 2's save logic wrong (re-check Task 2 step 2's parent-check).
- `OtelPropagationIT` "Skipped: 0" → assertion ran and failed (would mean OTel SDK got picked up somehow).
- Other IT fails → likely unrelated regression; do NOT commit.

- [ ] **Step 3: Stage all Round 9 changes**

Run:
```bash
git add methodTraceLog/src/main/java/cn/wubo/method/trace/log/LogAspect.java
git add methodTraceLog/src/main/java/cn/wubo/method/trace/log/impl/monitor/SimpleMonitorServiceImpl.java
git add methodTraceLog-test/src/test/java/cn/wubo/method/trace/log/e2e/OtelPropagationIT.java
git add CLAUDE.md
```

- [ ] **Step 4: Commit (single Round 9 commit)**

Run:
```bash
git commit -m "fix(round-9): wire cross-instance pspanid end-to-end (Ruling 6 resolution)

Long-standing product bug: LogAspect read pspanid from MDC key 'spanid', but
W3CTraceContextPropagator writes upstream parent-id to MDC key 'pspanid'.
Result: cross-instance inbound traces had pspanid==null, breaking parent/child
linkage. Ruling 6 (Round 7) documented it; Round 8 deferred the fix because the
naive LogAspect-only change broke SimpleMonitorServiceImpl's
'pspanid==null means root' simplification.

This Round 9 fixes it as a coordinated two-file change:

- LogAspect.java:168: pspanid = prespanid != null ? prespanid : prepspanid
  (in-process nested uses LOG_SPAN_ID; cross-instance inbound uses LOG_PSAN_ID)
- SimpleMonitorServiceImpl: save condition changed from 'pspanid == null' to
  'no in-process parent in methodTraceInfoMap' — captures both true roots AND
  cross-instance inbounds (whose parent is in another JVM)
- OtelPropagationIT: KNOWN GAP assertion flipped isNull() → isNotNull()
  (encodes the post-fix contract; will FAIL if LogAspect regresses)
- CLAUDE.md: deferral language removed

All 214 tests still pass (188 unit + 26 e2e + 1 OTel best-effort skip).

Co-Authored-By: Claude <noreply@anthropic.com>"
```

- [ ] **Step 5: Verify commit landed**

Run: `git log --oneline -1`
Expected: HEAD shows the Round 9 commit message.

---

### Task 6: Update TEST_REPORT.md with Round 9 section

**Files:**
- Modify: `TEST_REPORT.md` (append section)

**Interfaces:**
- Consumes: Round 9 commit info from Task 5
- Produces: Round 9 section in TEST_REPORT.md

- [ ] **Step 1: Append Round 9 section**

Run (via Bash):
```bash
cat >> TEST_REPORT.md << 'EOF'

---

## Round 9 — Complete pspanid Cross-Instance Fix (2026-08-29)

**Goal:** End-to-end fix for the `pspanid` MDC key mismatch — `LogAspect` correctly derives `pspanid` from the upstream `traceparent` header's `parent-id`, AND `SimpleMonitorServiceImpl` persists cross-instance inbound traces as top-level entries.

**Background:** Ruling 6 (Round 7) identified the product gap; Round 8 deferred the fix because the naive LogAspect-only change broke the store's "pspanid==null means root" assumption. Round 9 fixes it as a coordinated two-file change.

### Changes

- `methodTraceLog/src/main/java/cn/wubo/method/trace/log/LogAspect.java:168`
  - Before: `pspanid = prespanid`
  - After: `pspanid = prespanid != null ? prespanid : prepspanid`
  - In-process nested (prespanid non-null): use calling span's id. Cross-instance inbound (prespanid null): use upstream parent's span id from traceparent.

- `methodTraceLog/src/main/java/cn/wubo/method/trace/log/impl/monitor/SimpleMonitorServiceImpl.java`
  - Save condition changed from `pspanid == null` to `methodTraceInfoMap.get(pspanid) == null`.
  - True root (pspanid null) → save. Cross-instance inbound (pspanid set but parent not in our in-memory map) → save as top-level entry. In-process nested (parent IS in map) → attach as child, no save.

- `methodTraceLog-test/src/test/java/cn/wubo/method/trace/log/e2e/OtelPropagationIT.java`
  - KNOWN GAP assertion: `isNull()` → `isNotNull()` (encodes post-fix contract).

- `CLAUDE.md`: deferral language removed.

### Verification

- All 214 tests pass (188 unit + 26 e2e + 1 OTel best-effort skip).
- `TracePropagationIT`: cross-instance inbound trace now appears in `/view/list` (test still passes 2/2).
- `OtelPropagationIT`: still skips in default config (OTel SDK not loaded); KNOWN GAP signal will FAIL if LogAspect regresses.

### Behavioral changes

- `/view/list` now returns BOTH true roots AND cross-instance inbound traces (was: only true roots).
- `/view/traceid?id=` returns the full tree for any traceid, including cross-instance inbound trees.
- The web panel will display cross-instance inbounds as top-level entries — cosmetic consideration for future rounds.

### Known limitations (deferred to future rounds)

- Same-JVM cross-thread propagation not tested (no specific scenario where this matters in current code).
- Panel UI may want a visual distinction between "true root" (pspanid null) and "cross-instance inbound" (pspanid set to upstream) — tracked as panel polish item.
EOF
echo "TEST_REPORT.md updated"
```

- [ ] **Step 2: Commit TEST_REPORT.md**

Run:
```bash
git add TEST_REPORT.md
git commit -m "docs(round-9): add Round 9 section to TEST_REPORT (pspanid fix)"
```

- [ ] **Step 3: Verify both commits landed**

Run: `git log --oneline -3`
Expected: HEAD shows Round 9 docs commit; HEAD~1 shows Round 9 fix commit.

---

## Self-Review Checklist (writer runs mentally)

1. **Spec coverage:** Each requirement (LogAspect fix, SimpleMonitorServiceImpl fix, test flip, doc cleanup, report) maps to a task. ✓
2. **No placeholders:** No "TBD" / "TODO" / "similar to Task N". Every code step has the actual replacement code. ✓
3. **Type consistency:** `pspanid` semantics consistent across Tasks 1-2. `methodTraceInfoMap` referenced consistently. Task 3's `isNotNull()` matches Task 1's new behavior. ✓
4. **Multi-instance discipline:** Only `TracePropagationIT` needs dual instances. Task 3 step 3 explicitly verifies this. ✓
5. **Commit cadence:** Single Round 9 commit (Task 5 step 4) per Global Constraints; separate docs commit for TEST_REPORT.md. ✓
6. **Build discipline:** Every Task ends with `mvn install` (starter changes) or `mvn test` (test changes) before continuing. ✓
7. **Final task:** Report + verification, with explicit pass/fail signals per IT class. ✓
