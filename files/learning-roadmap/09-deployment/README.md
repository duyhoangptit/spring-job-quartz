# 09. Deployment — CI/CD, Blue-Green, Canary

## Mục tiêu
Triển khai hệ thống banking mà không gây downtime hoặc rủi ro cho giao dịch đang chạy.

## Kiến thức cốt lõi
- **CI/CD Pipeline**: build → test → scan → package → deploy, dùng GitHub Actions/GitLab CI/Jenkins. Pipeline phải fail-fast (test nhanh chạy trước).
- **Deployment Strategies**:
  - **Blue-Green**: 2 môi trường song song, switch traffic tức thì, rollback nhanh nhưng tốn gấp đôi tài nguyên.
  - **Canary**: release dần dần (5% → 25% → 100%) traffic vào version mới, theo dõi metrics trước khi tăng tỷ lệ.
  - **Rolling update**: Kubernetes mặc định, thay thế pod dần dần.
- **Database Migration trong Deployment**: Flyway/Liquibase, nguyên tắc **backward-compatible migration** (expand-contract pattern) để không breaking khi chạy song song 2 version code.
- **Feature Flags**: tách deploy khỏi release — deploy code mới nhưng chưa bật tính năng cho user, giảm rủi ro.
- **Rollback strategy**: phải test được rollback, không chỉ test được deploy.

## Điểm cần chú ý
- **Schema migration không tương thích ngược** là nguyên nhân phổ biến gây downtime khi deploy — VD: xoá column mà code cũ vẫn đang chạy sẽ crash. Dùng expand-contract: thêm column mới → deploy code dùng cả 2 → migrate data → xoá column cũ ở lần deploy sau.
- Deploy vào giờ cao điểm giao dịch (banking có pattern giờ cao điểm rõ rệt: đầu giờ sáng, cuối ngày, đầu tháng) là rủi ro không cần thiết — cần defined deployment window.
- Canary release nhưng không có metrics/alerting đủ nhanh để phát hiện vấn đề trước khi tăng traffic = canary vô nghĩa.
- Rollback plan chỉ tồn tại trên giấy, chưa từng thực hành thực tế — cần diễn tập định kỳ (game day).

## Ứng dụng vào Banking High-Concurrency
- Dùng **Canary release** cho `transaction-service` vì đây là service critical nhất — theo dõi tỷ lệ lỗi, latency P99, và **business metric** (tỷ lệ giao dịch thành công) trước khi tăng traffic, không chỉ theo dõi technical metrics.
- Expand-contract migration cho bảng `account`/`transaction` để đảm bảo zero-downtime khi có hàng nghìn giao dịch đang xử lý tại thời điểm deploy.
- Feature flag cho các tính năng mới (VD: fraud detection rule mới) để bật/tắt tức thì nếu phát hiện false positive cao mà không cần rollback deploy.

## Bài tập thực hành
Thiết lập pipeline CI/CD cho `transaction-service` với GitHub Actions: chạy test → build Docker image → push registry → deploy canary 10% lên môi trường staging, viết script kiểm tra metrics tự động trước khi promote lên 100%.

## Tài nguyên
- "Continuous Delivery" — Jez Humble & David Farley (nền tảng tư duy, không lỗi thời)
- Martin Fowler — bài viết "BlueGreenDeployment" và "CanaryRelease"
