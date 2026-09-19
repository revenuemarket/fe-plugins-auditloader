// 수정한 배치 상한 로직만 떼어 검증한다. LoadWorker 루프를 그대로 흉내낸다:
//   poll -> assembleAudit(event) -> loadIfNecessary()
import java.util.ArrayList;
import java.util.List;

public class BatchCapCheck {
    static final long MAX_BATCH = 50L * 1024 * 1024;  // 50MB
    static final int MAX_STMT = 1048576;              // 1MB
    static List<Integer> batch = new ArrayList<>();
    static long batchBytes;
    static int flushes;
    static long peakBytes;

    static long estimate(int stmtLen) { return Math.min(stmtLen, MAX_STMT); }

    static void assemble(int stmtLen) {
        batch.add(stmtLen);
        batchBytes += estimate(stmtLen);
        if (batchBytes > peakBytes) peakBytes = batchBytes;
    }

    static void loadIfNecessary(long elapsedMs, long intervalMs) {
        if (batchBytes < MAX_BATCH && elapsedMs < intervalMs) return;
        if (batch.isEmpty()) return;
        flushes++;
        batch.clear();
        batchBytes = 0L;
    }

    static void reset() { batch.clear(); batchBytes = 0; flushes = 0; peakBytes = 0; }

    public static void main(String[] a) {
        int fail = 0;

        // ① 사고 재현: 1분치 실측(1,527건 × 평균 100KB ≈ 145MB). 타이머는 안 왔다고 본다.
        reset();
        for (int i = 0; i < 1527; i++) { assemble(100_000); loadIfNecessary(0, 60_000); }
        long boundMB = (MAX_BATCH + MAX_STMT) / 1024 / 1024;
        if (peakBytes > MAX_BATCH + MAX_STMT) { System.out.println("✗ 상한 초과: " + peakBytes); fail++; }
        if (flushes < 2) { System.out.println("✗ 크기 기반 플러시가 안 돌았다: " + flushes); fail++; }
        System.out.printf("① 1527건×100KB → 플러시 %d회, 최대 배치 %.1fMB (상한 %dMB)%n",
                flushes, peakBytes / 1024.0 / 1024.0, boundMB);

        // ② 옛 로직이었다면? (원소 개수 vs 50MB) → 플러시 0회, 전부 힙에 쌓임
        long oldBytes = 0; int oldFlushes = 0;
        for (int i = 0; i < 1527; i++) {
            oldBytes += 100_000;
            long events = i + 1;
            if (!(events < MAX_BATCH && 0 < 60_000)) { oldFlushes++; oldBytes = 0; }
        }
        if (oldFlushes != 0) { System.out.println("✗ 옛 로직이 플러시했다고 나옴"); fail++; }
        System.out.printf("② 옛 로직 → 플러시 %d회, 누적 %.1fMB ← FE 를 죽인 값%n",
                oldFlushes, oldBytes / 1024.0 / 1024.0);

        // ③ 큰 이벤트 하나도 반드시 담긴다(영구 정체 없음)
        reset();
        assemble(MAX_STMT); loadIfNecessary(0, 60_000);
        if (flushes != 0 || batch.size() != 1) {
            // 1MB 는 50MB 미만이므로 아직 플러시 안 되는 게 정상
            System.out.println("✗ 1MB 이벤트 처리 이상: flushes=" + flushes + " size=" + batch.size()); fail++;
        }
        System.out.println("③ 1MB 이벤트 1건 → 담김(size=" + batch.size() + "), 아직 플러시 안 함");

        // ④ 타이머로도 플러시된다(적은 양)
        reset();
        assemble(1000); loadIfNecessary(60_001, 60_000);
        if (flushes != 1 || batchBytes != 0) { System.out.println("✗ 타이머 플러시 실패"); fail++; }
        System.out.println("④ 타이머 경과 → 플러시 " + flushes + "회, 잔여 " + batchBytes + "B");

        // ⑤ 빈 배치는 플러시하지 않는다
        reset();
        loadIfNecessary(60_001, 60_000);
        if (flushes != 0) { System.out.println("✗ 빈 배치를 플러시했다"); fail++; }
        System.out.println("⑤ 빈 배치 + 타이머 경과 → 플러시 " + flushes + "회");

        System.out.println(fail == 0 ? "\n모두 통과" : "\n" + fail + "건 실패");
        System.exit(fail == 0 ? 0 : 1);
    }
}
