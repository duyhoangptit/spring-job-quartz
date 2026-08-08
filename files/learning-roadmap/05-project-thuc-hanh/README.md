# 05. Dự án xuyên suốt — Core Banking Transaction Service

## Mục tiêu
Một dự án đủ phức tạp để áp dụng toàn bộ kiến thức từ mục 01 → 13, đóng vai trò "sợi chỉ đỏ" xuyên suốt lộ trình.

## Đề bài
Xây dựng **Core Banking Transaction Service**: hệ thống xử lý chuyển tiền giữa các tài khoản, mô phỏng các ràng buộc thực tế của ngân hàng.

### Chức năng bắt buộc
1. Tạo tài khoản, truy vấn số dư
2. Chuyển tiền giữa 2 tài khoản (atomic, idempotent theo `transaction_id` do client gửi lên)
3. Truy vấn lịch sử giao dịch (sao kê) có phân trang
4. Giới hạn hạn mức giao dịch/ngày theo tài khoản
5. Audit trail đầy đủ, không thể sửa/xoá (append-only)

### Ràng buộc phi chức năng (tăng dần theo từng giai đoạn học)
| Giai đoạn học | Yêu cầu bổ sung cho dự án |
|---|---|
| 01 Java Core | Xử lý đúng khi 1000 request đồng thời vào cùng 1 tài khoản |
| 02 Spring Boot | Audit trail qua AOP, health check qua Actuator |
| 03 PostgreSQL | Schema chuẩn hoá, index đúng, isolation level phù hợp |
| 04 Spring Data JPA | Optimistic locking, tránh N+1 ở API sao kê |
| 04b Design Patterns & Rule Engine | Fee Calculation Engine dùng Strategy pattern, không sửa code cũ khi thêm hạng tài khoản mới |
| 06 LinkedIn/Thương hiệu cá nhân | Viết case study kỹ thuật đầu tiên từ chính dự án này |
| 07 Testing | Tách thành 2 service (`account-service`, `transaction-service`) giao tiếp qua REST + Kafka (xem `12-microservices/PHU-LUC-tich-hop-linking.md`); coverage đầy đủ test pyramid, contract test giữa 2 service |
| 08 Docker | Containerize cả 2 service, docker-compose cho local dev |
| 09 Deployment | CI/CD pipeline tự động deploy khi merge vào main |
| 10 Security | OAuth2 (client credentials giữa service, password/PKCE cho client), mã hoá dữ liệu nhạy cảm |
| 11 Terraform | Provision hạ tầng (RDS, ECS/EKS, VPC) bằng Terraform, không click tay trên console |
| 12 Microservices | Áp dụng Saga pattern cho giao dịch xuyên service, CQRS cho phần đọc sao kê |
| 13 AI Engineering | Thêm module phát hiện giao dịch bất thường (fraud detection) dùng LLM/rule engine |

## Tiêu chí nghiệm thu cuối cùng (Definition of Done)
- Chịu tải **1000 TPS** giả lập (dùng k6/Gatling) với P99 latency < 200ms
- Không mất/trùng tiền dưới bất kỳ kịch bản concurrent nào (có test chứng minh)
- Zero-downtime deploy
- Toàn bộ hạ tầng reproducible bằng `terraform apply`
- Audit log đầy đủ, immutable, truy vết được từng thay đổi số dư

## Cấu trúc thư mục gợi ý cho dự án
```
core-banking-service/
├── account-service/
├── transaction-service/
├── fraud-detection-service/      # thêm ở giai đoạn 13
├── infra/                        # Terraform
├── docker-compose.yml
├── .github/workflows/            # CI/CD
└── docs/
    └── architecture-decision-records/   # ADR cho mỗi quyết định lớn
```

> **Gợi ý**: dùng Claude Code để scaffold từng service theo cấu trúc này, đồng thời sinh ADR (Architecture Decision Record) mỗi khi có quyết định kiến trúc quan trọng — thói quen tốt cho technical leader.
