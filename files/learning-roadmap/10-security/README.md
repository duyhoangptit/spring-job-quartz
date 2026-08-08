# 10. Security — AuthN/AuthZ, OWASP, PCI-DSS

## Mục tiêu
Bảo mật hệ thống banking đạt chuẩn compliance thực tế, không chỉ "có login là đủ".

## Kiến thức cốt lõi
- **AuthN vs AuthZ**: OAuth2 (Authorization Code + PKCE cho client app, Client Credentials cho service-to-service), OIDC cho identity, JWT (hiểu rõ rủi ro nếu không validate `alg`, `exp`, `aud` đúng cách).
- **mTLS**: xác thực 2 chiều giữa các service nội bộ — chuẩn bắt buộc trong nhiều hệ thống banking cho service mesh.
- **OWASP Top 10**: đặc biệt Injection, Broken Access Control, Cryptographic Failures — áp dụng trực tiếp vào code Spring (Prepared Statement qua JPA, `@PreAuthorize` cho method-level authorization).
- **Secrets Management**: Vault/AWS Secrets Manager/Azure Key Vault — không bao giờ hardcode hoặc để secret trong biến môi trường plaintext lộ trong log.
- **Encryption**: at-rest (DB encryption, disk encryption) và in-transit (TLS 1.2+), field-level encryption cho dữ liệu nhạy cảm (số thẻ, thông tin định danh).
- **PCI-DSS cơ bản** (nếu hệ thống chạm vào dữ liệu thẻ): tokenization, không lưu CVV, network segmentation.
- **Rate Limiting & Anti-fraud tầng API**: bảo vệ khỏi brute-force, credential stuffing.

## Điểm cần chú ý
- Xác thực (AuthN) xong tưởng là đủ, nhưng thiếu **authorization ở tầng method/data** (VD: user A gọi API xem giao dịch của user B chỉ vì đổi ID trên URL — IDOR) là lỗi cực kỳ phổ biến và nghiêm trọng trong banking.
- Log chứa thông tin nhạy cảm (số tài khoản đầy đủ, số dư) vi phạm compliance — cần masking ở tầng logging framework, không phải nhớ tay che từng chỗ.
- JWT dùng để lưu quá nhiều thông tin nhạy cảm hoặc không có cơ chế revoke (banking cần logout/revoke tức thì) — cân nhắc short-lived token + refresh token với revocation list.
- Rate limiting chỉ đặt ở tầng gateway mà không có business-level fraud detection (VD: 1 tài khoản chuyển tiền 50 lần/phút dù dưới rate limit kỹ thuật) vẫn để lọt hành vi bất thường.

## Ứng dụng vào Banking High-Concurrency
- Service-to-service giao tiếp qua **mTLS + OAuth2 Client Credentials**, không có service nào tin tưởng ngầm định lẫn nhau (zero-trust).
- **Field-level encryption** cho số tài khoản/thông tin định danh trong DB, key quản lý qua KMS riêng biệt với application.
- Thiết kế **rate limiting đa tầng**: network (WAF), API gateway (theo IP/user), business logic (theo pattern hành vi) — vì high-concurrency system cũng là mục tiêu hấp dẫn cho tấn công DDoS/credential stuffing.

## Bài tập thực hành
Implement `@PreAuthorize` cho toàn bộ API trong `account-service` đảm bảo user chỉ truy cập được tài khoản của chính mình (trừ role admin), viết test chứng minh IDOR bị chặn, và cấu hình field-level encryption cho column chứa số tài khoản.

## Tài nguyên
- OWASP Top 10 (bản mới nhất) — đọc gốc từ owasp.org
- OWASP Cheat Sheet Series — "Authentication", "Access Control"
- Spring Security Reference Docs
