# 11. Terraform / Infrastructure as Code

## Mục tiêu
Provision toàn bộ hạ tầng banking bằng code, reproducible, review được như review code ứng dụng.

## Kiến thức cốt lõi
- **Terraform core concepts**: State file (và vì sao phải dùng remote state + locking, VD: S3 + DynamoDB lock), Plan/Apply workflow, Provider, Module.
- **Module hoá**: tách hạ tầng thành module tái sử dụng (VPC, RDS, EKS/ECS) — quan trọng khi tổ chức có nhiều team/nhiều môi trường (dev/staging/prod).
- **Workspace & Environment separation**: tách state theo môi trường, tránh 1 lệnh `apply` sai làm ảnh hưởng production.
- **Hạ tầng cho hệ thống banking điển hình**: VPC với subnet private cho DB, security group theo nguyên tắc least privilege, RDS Multi-AZ cho PostgreSQL, EKS/ECS cho container, ALB/NLB, KMS cho encryption key.
- **Drift detection**: `terraform plan` định kỳ để phát hiện thay đổi thủ công ngoài code (một trong những vi phạm compliance phổ biến nhất).
- **Policy as Code**: OPA/Sentinel để enforce rule hạ tầng (VD: không được tạo security group mở 0.0.0.0/0 vào port DB).

## Điểm cần chú ý
- **State file chứa thông tin nhạy cảm** (password, key) ở dạng plaintext — phải encrypt remote state và giới hạn quyền truy cập chặt chẽ.
- Không dùng remote state + locking khi có nhiều người cùng apply → race condition làm hỏng state, gây hạ tầng "ma" (resource tồn tại nhưng Terraform không biết).
- Hardcode giá trị thay vì dùng variable/module → không reproducible giữa các môi trường, dev và prod lệch cấu hình mà không ai biết.
- Thiếu `prevent_destroy` lifecycle rule cho resource critical (RDS, KMS key) → 1 lệnh `terraform destroy` nhầm có thể xoá cả database production.

## Ứng dụng vào Banking High-Concurrency
- Module hoá VPC/RDS/EKS cho phép **tạo môi trường staging giống hệt production** để load test chính xác, phát hiện vấn đề trước khi lên production.
- RDS PostgreSQL cấu hình **Multi-AZ + read replica** bằng Terraform để scale đọc (sao kê, báo cáo) tách khỏi tải ghi (giao dịch).
- Auto-scaling group/EKS HPA (Horizontal Pod Autoscaler) định nghĩa bằng code, scale theo CPU/custom metric (số request/giây) để đáp ứng traffic pattern giờ cao điểm banking mà không cần can thiệp thủ công.
- Compliance: mọi thay đổi hạ tầng đi qua Pull Request + review, tạo audit trail tự nhiên cho auditor.

## Bài tập thực hành
Viết Terraform module cho hạ tầng `core-banking-service`: VPC 3-tier (public/private/data subnet), RDS PostgreSQL Multi-AZ trong private subnet, EKS cluster, security group least-privilege, output đầy đủ để CI/CD pipeline sử dụng.

## Tài nguyên
- Terraform Official Docs — "Module", "State" (đọc gốc)
- "Terraform: Up & Running" — Yevgeniy Brikman
