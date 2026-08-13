# Tài Liệu Thiết Kế: Hệ Thống Giám Sát Và Tự Động Sửa Lỗi Qua AI Agent (Claude)

Tài liệu này mô tả kiến trúc, quy trình vận hành và các thành phần cốt lõi để xây dựng hệ thống tự động giám sát (Monitoring), cảnh báo qua Telegram, phân tích lỗi bằng Claude AI và kích hoạt quy trình tự động sửa lỗi/triển khai lại (Auto-Healing & Auto-Deploy).

---

## 1. Tổng Quan Kiến Trúc Hệ Thống

Hệ thống hoạt động theo mô hình **Event-Driven (Kiến trúc hướng sự kiện)** thông qua một ứng dụng trung gian (Middleware Engine). Claude AI đóng vai trò là "Bộ não phân tích", không trực tiếp can thiệp vào hạ tầng mà tương tác thông qua các API và công cụ CI/CD bảo mật.

### Sơ Đồ Quy Trình Hoạt Động (Workflow)

```text
[Hệ thống / Giám sát] ──(1. Metrics bất thường)──> [Middleware Engine]
                                                           │
[Telegram User] <──(3. Duyệt: Yes/No)── [Claude Agent] ◄───┘ (2. Gửi Error Log & Prompt)
       │
       └─(4. Approved)──> [CI/CD Pipeline / Script] ──(5. Fix & Re-deploy)──> [Hệ thống sạch]
```

---

## 2. Thành Phần Cốt Lõi (Core Components)

### 2.1. Tầng Giám Sát & Cảnh Báo (Monitoring & Alerting)
*   **Công cụ khuyến nghị:** Prometheus + Alertmanager hoặc Grafana Alerting.
*   **Nhiệm vụ:** Theo dõi liên tục các chỉ số tài nguyên (CPU, RAM, Disk) và trạng thái ứng dụng (HTTP 5xx, Crash Loop).
*   **Hành động:** Khi kích hoạt cảnh báo (Alert trigger), hệ thống sẽ gom **20-50 dòng log lỗi mới nhất** và gửi Webhook tới *Middleware Engine*.

### 2.2. Bộ Não Kết Nối (Middleware Engine)
*   **Công nghệ:** Viết bằng Python (FastAPI/Flask) hoặc Node.js.
*   **Nhiệm vụ:**
    *   Tiếp nhận Webhook từ tầng giám sát.
    *   Đóng gói thông tin lỗi vào cấu trúc Prompt tối ưu.
    *   Gọi **Claude API (Anthropic)** để lấy phân tích và giải pháp.
    *   Xử lý phản hồi từ Claude và điều hướng lệnh đến Telegram / CI/CD.

### 2.3. Cổng Tương Tác (Telegram Bot Gateway)
*   **Công cụ:** Telegram Bot API (sử dụng thư viện `python-telegram-bot` hoặc tương đương).
*   **Giao diện:** Tin nhắn dạng văn bản (Markdown) kèm theo hệ thống nút bấm tương tác nhanh (**Inline Keyboards**) như `[✅ Approve Fix]` và `[❌ Reject]`.

### 2.4. Tầng Tự Động Triển Khai (Auto-Healing & CI/CD)
*   **Công cụ:** GitHub Actions, GitLab CI, Ansible hoặc Shell Script bảo mật.
*   **Nhiệm vụ:** Nhận lệnh thực thi sau khi User bấm Approve trên Telegram. Thực hiện vá lỗi (hotfix), dọn dẹp tài nguyên hoặc khởi động lại dịch vụ và tự động deploy lại phiên bản sạch.

---

## 3. Kịch Bản Tương Tác Chi Tiết (Detailed Workflow)

### Bước 1: Phát hiện sự cố & Gọi Claude AI
Khi hệ thống gặp lỗi (Ví dụ: Tràn RAM hoặc Lỗi kết nối Cơ sở dữ liệu), Middleware sẽ gửi yêu cầu đến Claude API với cấu trúc:
*   **System Prompt:** *"Bạn là một Kỹ sư SRE/DevOps cao cấp. Hãy phân tích đoạn log lỗi sau, tìm nguyên nhân gốc rễ và đề xuất duy nhất 1 phương án khắc phục ngắn gọn kèm lệnh/code thực thi cụ thể."*
*   **User Prompt:** Đính kèm Metrics hệ thống + Đoạn log lỗi vừa cào được.

### Bước 2: AI Agent Báo Cáo & Xin Quyền Qua Telegram
Middleware nhận kết quả từ Claude và bắn tin nhắn đến Telegram của Quản trị viên:

> 🚨 **CẢNH BÁO HỆ THỐNG BẤT THƯỜNG**
> *   **Sự cố:** Ứng dụng Backend bị Crash (Lỗi HTTP 502)
> *   **Phân tích từ Claude:** Cơ sở dữ liệu (PostgreSQL) bị từ chối kết nối do vượt quá `max_connections`.
> *   **Giải pháp đề xuất:** Thực hiện script tối ưu hóa kết nối, giải phóng các session treo và tăng cấu hình `max_connections` tạm thời, sau đó khởi động lại service.
>
> *Bạn có đồng ý cho phép AI thực hiện sửa lỗi và deploy lại hệ thống không?*
>
> `[✅ Approve & Deploy]`  |  `[❌ Reject / Ignore]`

### Bước 3: Phê duyệt và Tự Động Deploy (Auto-Deploy)
1.  Người dùng bấm nút **`[✅ Approve & Deploy]`**.
2.  Telegram gửi mã Callback Webhook về Middleware.
3.  Middleware xác thực đúng Chat ID của chủ sở hữu (Bảo mật).
4.  Middleware gọi API kích hoạt **CI/CD Pipeline**:
    *   Chạy Script sửa lỗi tự động do Claude chuẩn bị sẵn (hoặc kịch bản định sẵn).
    *   Kiểm tra tính toàn vẹn (Health check).
    *   Bắn thông báo hoàn tất lên Telegram: *"Hệ thống đã được fix lỗi thành công và tự động Re-deploy thành công vào lúc 15:30!"*

---

## 4. Nguyên Tắc Bảo Mật & Giới Hạn (Security & Guardrails)

*   **Nguyên tắc đặc quyền tối thiểu (Least Privilege):** Bot CI/CD và Script sửa lỗi chỉ được cấp quyền trong phạm vi ứng dụng cụ thể. Tuyệt đối không cấp quyền Root toàn hệ tầng cho AI.
*   **Xác thực Chat ID cứng (Hardcoded Admin Chat ID):** Bot Telegram chỉ phản hồi và nhận lệnh từ chính xác Chat ID của bạn. Tất cả các request từ ID lạ sẽ bị block lập tức.
*   **Giới hạn Token (Context Window):** Chỉ gửi tối đa 50 dòng log quan trọng nhất để tránh lãng phí chi phí API và ngăn hiện tượng AI bị "ảo tưởng" (Hallucination) khi đọc lượng dữ liệu quá lớn.
*   **Human-in-the-loop (Luôn cần con người duyệt):** Tuyệt đối không bật chế độ "Auto-Fix" 100% không qua phê duyệt đối với các môi trường Production sản xuất để tránh rủi ro AI xóa nhầm dữ liệu.

 