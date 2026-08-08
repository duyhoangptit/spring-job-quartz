# Lộ trình Java Backend Engineer → Microservices → AI Engineering
### (Đích đến: Hệ thống Banking High-Concurrency)

> Người học: Technical Leader, 10 năm kinh nghiệm (Node.js, Java, Go, Python, Angular/React/Vue, AWS/Azure cơ bản)
> Domain: Telecom, Finance, Healthcare, Logistics, Insurance
> Ghi chú: Vì bạn đã có nền tảng đa ngôn ngữ + kinh nghiệm leadership, lộ trình này **không dạy lại cú pháp cơ bản**, mà tập trung vào: (1) triết lý & idioms riêng của hệ sinh thái Java/Spring, (2) các quyết định kiến trúc, (3) vận hành production-grade, (4) case study banking thực tế.

---

## 1. Cấu trúc thư mục

```
learning-roadmap/
├── 00-overview/                 # File này + cách dùng lộ trình
├── 01-java-core/                # JVM, Concurrency, Memory Model
├── 02-spring-boot/              # DI/IoC, Auto-config, Actuator
├── 03-postgresql/                # Indexing, Isolation Level, Locking
├── 04-spring-data-jpa/          # ORM, N+1, Transaction boundary
├── 04b-design-patterns-rule-engine/  # Design pattern & Rule Engine cho nghiệp vụ phức tạp
├── 05-project-thuc-hanh/        # Đề bài dự án xuyên suốt lộ trình
├── 06-linkedin-personal-branding/ # Xây dựng profile LinkedIn, thương hiệu cá nhân
├── 07-testing/                  # Unit/Integration/Contract/Load test
├── 08-docker-container/         # Image, layer, multi-stage build
├── 09-deployment/               # CI/CD, blue-green, canary
├── 10-security/                 # AuthN/AuthZ, OWASP, PCI-DSS
├── 11-terraform-iac/            # IaC cho hạ tầng banking
├── 12-microservices/            # Saga, CQRS, Event sourcing
├── 13-ai-engineering/           # LLM integration, RAG, AI trong banking
└── case-studies/
    └── banking-high-concurrency.md   # Bài toán tổng hợp cuối lộ trình
```

Mỗi thư mục con có `README.md` với format chuẩn:
- **Mục tiêu** – biết làm gì sau khi học xong
- **Kiến thức cốt lõi** – checklist các khái niệm phải nắm chắc
- **Điểm cần chú ý** – pitfalls, sai lầm thường gặp (đặc biệt ở production)
- **Ứng dụng vào Banking High-Concurrency** – nối trực tiếp lý thuyết với bài toán thực tế
- **Bài tập thực hành** – gắn với dự án xuyên suốt ở mục 05
- **Tài nguyên** – sách/doc/RFC nên đọc (không liệt kê khóa học tràn lan)

---

## 2. Nguyên tắc học (Phương pháp luận)

1. **Depth-first trên từng layer, breadth-first trên toàn hệ thống**: học đủ sâu 1 công nghệ để hiểu trade-off, nhưng đừng đào quá sâu trước khi thấy được bức tranh tổng thể.
2. **Học qua vấn đề, không học qua tính năng**: mỗi mục đều bắt đầu từ câu hỏi "hệ thống banking triệu giao dịch/ngày gặp vấn đề gì ở layer này?" rồi mới tra API.
3. **Một dự án xuyên suốt** (`05-project-thuc-hanh`): mô phỏng hệ thống **Core Banking Transaction Service** — mọi kiến thức mới học đều được áp dụng ngay vào dự án này, không học xong rồi để đó.
4. **Đo lường bằng production readiness**, không phải bằng "chạy được demo". Mỗi giai đoạn có tiêu chí nghiệm thu rõ ràng (xem từng README).

---

## 3. Trình tự học & thời lượng gợi ý (dành cho người đã có kinh nghiệm)

| Giai đoạn | Chủ đề | Thời lượng gợi ý | Output kỳ vọng |
|---|---|---|---|
| 1 | Java Core (JVM, Concurrency) | 1–1.5 tuần | Hiểu GC, Memory Model, viết code thread-safe không cần đoán mò |
| 2 | Spring Boot | 1 tuần | Hiểu cơ chế DI/AOP, tự viết auto-configuration |
| 3 | PostgreSQL | 1 tuần | Đọc được execution plan, thiết kế index cho OLTP |
| 4 | Spring Data JPA | 1 tuần | Kiểm soát transaction boundary, tránh N+1, dirty checking |
| 4b | Design Patterns & Rule Engine | 4–5 ngày | Thiết kế logic nghiệp vụ mở rộng được, biết khi nào cần Drools |
| 5 | Project thực hành #1 | 1 tuần | Core Banking Service v1 chạy được, có test |
| 6 | LinkedIn / Thương hiệu cá nhân | 3–4 ngày | Profile hoàn chỉnh, kế hoạch nội dung định kỳ |
| 7 | Testing | 1 tuần | Test pyramid đầy đủ, contract test giữa services |
| 8 | Docker/Container | 3–4 ngày | Image tối ưu, multi-stage, security scan |
| 9 | Deployment | 1 tuần | CI/CD pipeline, blue-green/canary release |
| 10 | Security | 1 tuần | OAuth2/OIDC, mTLS, PCI-DSS checklist |
| 11 | Terraform/IaC | 1 tuần | Provision hạ tầng banking (VPC, RDS, EKS) bằng code |
| 12 | Microservices patterns | 1.5–2 tuần | Saga, CQRS, Event Sourcing áp dụng vào Core Banking |
| 13 | AI Engineering | 1–1.5 tuần | Tích hợp LLM vào quy trình banking (fraud detection, chatbot, code review) |
| — | Case Study tổng hợp | 1 tuần | Thiết kế hệ thống banking chịu tải 10k TPS, review kiến trúc end-to-end |

Tổng: **~15–17 tuần** nếu học song song với công việc (10–15h/tuần). Có thể rút ngắn nếu bạn skip phần đã vững (VD: Docker, Testing tổng quát).

---

## 4. Cách dùng lộ trình này với Claude Code

Bạn có thể copy toàn bộ thư mục `learning-roadmap/` vào repo dự án, rồi dùng Claude Code để:
- Sinh code mẫu theo từng README
- Review code dự án theo checklist "Điểm cần chú ý"
- Tự động tạo bài test dựa trên tiêu chí nghiệm thu

Xem chi tiết bài toán tổng hợp tại `case-studies/banking-high-concurrency.md`.
