# 04. Spring Data JPA — ORM, N+1, Transaction Boundary

## Mục tiêu
Dùng JPA/Hibernate mà kiểm soát được SQL sinh ra, hiểu rõ transaction boundary để tránh lỗi tài chính do ORM "che giấu" hành vi thật.

## Kiến thức cốt lõi
- **Entity Lifecycle**: Transient → Persistent (managed) → Detached → Removed. Persistence Context (First-level cache) hoạt động trong phạm vi 1 transaction.
- **Dirty Checking**: Hibernate tự phát hiện thay đổi entity managed và tự động UPDATE khi flush — cần hiểu rõ để tránh update ngoài ý muốn.
- **Fetch strategy**: `LAZY` vs `EAGER`, nguyên nhân **N+1 query problem** và cách khắc phục (`JOIN FETCH`, `@EntityGraph`, batch fetching `hibernate.default_batch_fetch_size`).
- **Transaction boundary**: `@Transactional` propagation (`REQUIRED`, `REQUIRES_NEW`, `NESTED`), isolation level override, `readOnly = true` cho query.
- **Optimistic Locking**: `@Version` annotation, `OptimisticLockException` và chiến lược retry.
- **Repository pattern**: `JpaRepository`, `@Query` (JPQL vs native), Specification API cho dynamic query, Projection (DTO trực tiếp thay vì load full entity).

## Điểm cần chú ý
- **`@Transactional` trên private method hoặc self-invocation không hoạt động** — vì Spring AOP dùng proxy, gọi method nội bộ trong cùng class sẽ bỏ qua proxy. Đây là lỗi rất phổ biến khiến transaction "âm thầm không rollback".
- **N+1 query** là nguyên nhân số 1 gây chậm hệ thống dùng JPA khi scale — luôn kiểm tra SQL log (`show-sql` + `format_sql`) trước khi đưa code lên production.
- Transaction quá dài (gọi external API bên trong `@Transactional`) giữ connection pool và DB lock lâu — tách biệt I/O ra khỏi transaction boundary.
- `EAGER` fetch mặc định trên `@ManyToOne` là bẫy phổ biến — luôn set `LAZY` tường minh và fetch có chủ đích.
- Dirty checking có thể gây update ngoài ý muốn nếu entity bị load rồi sửa nhầm field trong logic không liên quan — cân nhắc dùng DTO/projection cho các luồng chỉ đọc.

## Ứng dụng vào Banking High-Concurrency
- Với bảng `account`, dùng **`@Version` (optimistic lock)** kết hợp retry ở tầng service cho thao tác cập nhật số dư — tránh phải giữ pessimistic lock lâu làm giảm throughput.
- Tách riêng **transaction ghi số dư** (ngắn, atomic) khỏi **transaction ghi lịch sử/audit** (có thể qua outbox pattern, xử lý bất đồng bộ) để giảm thời gian giữ lock trên bảng nóng.
- Dùng Projection/DTO cho các API truy vấn sao kê (read-heavy) để tránh load toàn bộ entity graph không cần thiết, giảm tải DB khi có hàng nghìn request đọc đồng thời.

## Bài tập thực hành
Refactor `TransactionProcessor` ở bài tập mục 01 để dùng Spring Data JPA: implement optimistic locking cho `Account`, viết test chứng minh `OptimisticLockException` được throw đúng khi 2 transaction ghi đè nhau, và implement retry logic với giới hạn số lần thử.

## Tài nguyên
- "High-Performance Java Persistence" — Vlad Mihalcea (bắt buộc đọc nếu làm hệ thống tài chính)
- Spring Data JPA Reference Docs — chương "Transactions"
