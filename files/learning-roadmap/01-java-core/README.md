# 01. Java Core — JVM, Concurrency, Memory Model

## Mục tiêu
Nắm vững cơ chế bên dưới JVM để viết code Java "đúng bản chất" thay vì viết Node.js/Go bằng cú pháp Java.

## Kiến thức cốt lõi
- **JVM Memory Model**: Heap (Young/Old Gen), Stack, Metaspace, Direct Memory (off-heap buffer cho I/O).
- **Garbage Collection**: G1GC (mặc định từ Java 9+), ZGC/Shenandoah cho low-latency. Hiểu Stop-The-World pause và cách tuning `-Xms -Xmx -XX:MaxGCPauseMillis`.
- **Java Memory Model (JMM)**: `happens-before`, visibility, `volatile`, tại sao double-checked locking cần `volatile`.
- **Concurrency primitives**: `synchronized` vs `ReentrantLock`, `ExecutorService`, `CompletableFuture`, `ForkJoinPool`.
- **Virtual Threads (Java 21+, Project Loom)**: khác biệt căn bản so với thread pool truyền thống — quan trọng cho hệ thống high-concurrency vì giảm chi phí context-switching khi có hàng chục nghìn connection đồng thời.
- **Immutability & Records**: giảm bug concurrency bằng thiết kế bất biến.

## Điểm cần chú ý (Pitfalls)
- **Thread pool sizing sai** là nguyên nhân phổ biến nhất gây nghẽn hệ thống banking khi traffic tăng đột biến — hiểu công thức `N_threads = N_cpu * U_cpu * (1 + W/C)` (Wait/Compute ratio) thay vì đoán số ngẫu nhiên.
- Đừng nhầm `synchronized` block dài với an toàn — lock quá rộng làm giảm throughput, lock quá hẹp gây race condition tinh vi.
- `CompletableFuture` không tự propagate exception nếu bạn quên `.exceptionally()` hoặc `.handle()` — lỗi âm thầm biến mất.
- Virtual Threads không phù hợp cho CPU-bound task hoặc code có `synchronized` block dài (gây pinning) — cần hiểu khi nào dùng, khi nào không.
- GC tuning sai cho hệ thống latency-sensitive (giao dịch banking cần P99 < 100ms) có thể khiến pause time GC trở thành nút thắt cổ chai chính.

## Ứng dụng vào Banking High-Concurrency
Hệ thống core banking xử lý hàng nghìn giao dịch/giây phải:
1. Dùng **Virtual Threads** cho các service I/O-bound (gọi DB, gọi service khác) để phục vụ hàng chục nghìn request đồng thời mà không kiệt tài nguyên OS thread.
2. Đảm bảo **idempotency** ở tầng concurrency: khi 2 request trùng transaction ID đến gần như đồng thời, cơ chế lock (`ReentrantLock` theo key, hoặc distributed lock ở Redis) phải ngăn double-processing.
3. Tuning GC để đảm bảo **SLA P99 latency** — banking thường yêu cầu SLA nghiêm ngặt hơn e-commerce.

## Bài tập thực hành
Trong dự án `05-project-thuc-hanh`: viết một `TransactionProcessor` xử lý đồng thời 1000 giao dịch giả lập, đảm bảo không có race condition khi cùng một tài khoản bị trừ tiền từ nhiều thread — dùng cả cách tiếp cận `synchronized`, `ReentrantLock`, và so sánh hiệu năng.

## Tài nguyên
- "Java Concurrency in Practice" — Brian Goetz (vẫn là sách nền tảng dù đã cũ)
- JEP 444 (Virtual Threads) — đọc trực tiếp JEP thay vì blog tóm tắt
- Oracle GC Tuning Guide (phần G1GC)
