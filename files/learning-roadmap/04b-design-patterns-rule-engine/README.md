# 04b. Design Patterns & Rule Engine — Xử lý nghiệp vụ phức tạp, dễ mở rộng

## Mục tiêu
Thiết kế logic nghiệp vụ (phí giao dịch, luật fraud, quy trình duyệt vay/KYC...) sao cho **thay đổi quy tắc nghiệp vụ không kéo theo sửa code lan rộng** — nguyên tắc Open-Closed Principle (OCP) áp dụng thực chiến, không chỉ lý thuyết SOLID.

## Vì sao mục này quan trọng và đặt ở đây
Trong banking, quy tắc nghiệp vụ thay đổi liên tục (biểu phí theo hạng tài khoản, ngưỡng cảnh báo fraud, điều kiện duyệt khoản vay theo sản phẩm/quốc gia) nhưng **hạ tầng kỹ thuật thì ổn định**. Nếu không tách biệt 2 thứ này, mỗi lần business đổi 1 con số ngưỡng, kỹ sư phải sửa code, test, deploy lại — vừa chậm vừa rủi ro cho hệ thống critical. Học phần này **trước khi** code dự án ở mục 05 để thiết kế đúng ngay từ đầu, không phải refactor sau.

## Kiến thức cốt lõi

### A. Design Patterns — công cụ cho từng loại biến thiên (variability)

| Pattern | Dùng khi nào | Ví dụ trong banking |
|---|---|---|
| **Strategy** | Nhiều thuật toán/quy tắc thay thế nhau cho cùng 1 hành vi | Tính phí giao dịch khác nhau theo hạng tài khoản (Standard/Premium/VIP) |
| **Factory / Abstract Factory** | Tạo object mà loại cụ thể phụ thuộc config/runtime | Tạo đúng `PaymentGatewayAdapter` theo ngân hàng đối tác được cấu hình |
| **Builder** | Object có nhiều field tuỳ chọn, tổ hợp phức tạp | Dựng `TransferRequest` với nhiều field optional (ghi chú, mã khuyến mãi, cờ miễn phí) |
| **Adapter** | Tích hợp hệ thống ngoài có interface khác biệt | Chuẩn hoá API của nhiều ngân hàng đối tác/cổng thanh toán về 1 interface chung nội bộ |
| **Chain of Responsibility** | Chuỗi bước xử lý tuần tự, mỗi bước có thể chặn/chuyển tiếp | Pipeline kiểm tra fraud: check hạn mức → check địa lý → check pattern bất thường |
| **Template Method** | Quy trình có khung chung, chi tiết từng bước khác theo loại | Quy trình duyệt khoản vay: khung chung giống nhau, bước thẩm định khác theo loại vay |
| **Decorator** | Thêm hành vi (logging, audit, retry) mà không sửa class gốc | Bọc `AccountService` bằng audit/logging decorator mà không đụng business logic |
| **Specification** | Tổ hợp điều kiện nghiệp vụ (AND/OR/NOT) linh hoạt | Xây rule fraud dạng `(hạn mức vượt AND địa lý lạ) OR (tần suất cao)` composable được |

### B. Rule Engine — khi pattern thuần code chưa đủ
- **Bản chất**: tách rule nghiệp vụ ra khỏi code, cho phép người không phải dev (business analyst, compliance officer) đọc/sửa rule mà không cần deploy.
- **2 hướng tiếp cận**:
  1. **Tự xây "rule engine nhẹ"** bằng chính các pattern ở trên (Specification + Strategy + Chain of Responsibility) — đủ dùng cho phần lớn hệ thống vừa và lớn, dev vẫn kiểm soát rule qua code/config, không cần học công cụ mới.
  2. **Dùng rule engine chuyên dụng** (Drools là phổ biến nhất trong hệ sinh thái Java) — rule viết bằng DRL hoặc Decision Table (Excel), business user tự sửa rule, có rule versioning — phù hợp khi rule thay đổi rất thường xuyên và người sửa không phải dev.
- **Trade-off**: Drools mạnh nhưng thêm độ phức tạp vận hành (learning curve, debug khó hơn code thuần, cần review rule như review code). Chỉ dùng khi lợi ích (business tự sửa rule, không cần dev) vượt chi phí phức tạp thêm vào.

## Điểm cần chú ý (Pitfalls)
- **Overengineering**: áp pattern vào chỗ không có biến thiên thực sự (chỉ có 1 cách làm duy nhất, không có kế hoạch mở rộng) làm code phức tạp hoá không cần thiết — chỉ áp dụng khi biến thiên là **có thật và có khả năng tăng**.
- **Factory/Strategy nở rộ không kiểm soát**: nhiều class Strategy nhưng không có nơi đăng ký tập trung → khó biết hệ thống đang có bao nhiêu strategy. Trong Spring, tận dụng `Map<String, FeeStrategy>` auto-wire tất cả bean cùng interface, tra cứu theo key thay vì `if-else` chọn class.
- **Rule không có test/kiểm tra xung đột**: 2 rule fraud mâu thuẫn nhau âm thầm (rule A cho qua giao dịch mà rule B lẽ ra phải chặn) là lỗi cực kỳ khó phát hiện nếu không có test case cho tổ hợp rule — cần bộ test riêng cho rule engine, độc lập với test code thông thường.
- **Rule hard-code trong DB không có versioning/audit** vi phạm yêu cầu compliance — mọi thay đổi rule (ai đổi, khi nào, đổi gì) phải có audit trail y hệt như audit trail thay đổi số dư (liên hệ mục 12 – Event Sourcing).
- **Nhầm lẫn giữa "biến thiên kỹ thuật" và "biến thiên nghiệp vụ"**: đổi database hay đổi framework là biến thiên kỹ thuật (dùng Adapter/DIP); đổi ngưỡng fraud hay biểu phí là biến thiên nghiệp vụ (dùng Strategy/Rule Engine) — dùng sai công cụ cho sai loại biến thiên gây thiết kế lệch mục tiêu.

## Ứng dụng vào Banking High-Concurrency
- **Fee Calculation Engine**: `Map<AccountType, FeeStrategy>` — thêm hạng tài khoản mới chỉ cần thêm 1 bean `FeeStrategy` mới, không sửa code cũ (đúng OCP), không cần deploy lại các phần khác của hệ thống.
- **Fraud Detection Pipeline** (liên hệ mục 13): Chain of Responsibility cho các bước kiểm tra tuần tự (nhanh, rẻ trước → chậm, tốn tài nguyên sau), kết hợp Specification pattern để tổ hợp điều kiện linh hoạt; với hệ thống cần business tự cấu hình ngưỡng thường xuyên, cân nhắc Drools cho riêng module này.
- **Payment Gateway Integration**: Abstract Factory chọn đúng `Adapter` (Vietcombank API, Visa/Mastercard, ví điện tử...) theo cấu hình runtime — thêm đối tác thanh toán mới không đụng vào code service hiện có.
- **Loan Approval Workflow**: Template Method định nghĩa khung quy trình chung (thu thập hồ sơ → thẩm định → phê duyệt → giải ngân), mỗi loại vay override bước thẩm định riêng — tránh copy-paste toàn bộ luồng cho mỗi sản phẩm vay mới.
- **Builder cho Transaction Request**: request chuyển tiền có nhiều field optional (mã khuyến mãi, ghi chú, cờ ưu tiên xử lý) — Builder giúp tạo object rõ ràng, tránh constructor với 10+ tham số.

## Bài tập thực hành
1. Trong dự án `05-project-thuc-hanh`, implement `FeeCalculationEngine` bằng Strategy pattern + Spring bean map — viết test chứng minh thêm `AccountType` mới không cần sửa class nào đã có (OCP compliance test).
2. Xây 1 rule engine nhẹ cho fraud detection dùng Specification pattern (rule composable AND/OR), sau đó thử implement lại cùng use case bằng Drools — so sánh độ phức tạp, thời gian phát triển, khả năng business tự sửa rule.
3. Viết Adapter cho 2 "cổng thanh toán giả lập" có interface khác nhau, dùng Abstract Factory để chọn đúng adapter theo config.

## Tài nguyên
- "Design Patterns: Elements of Reusable Object-Oriented Software" — Gang of Four (bản gốc, vẫn là tài liệu tham chiếu chuẩn)
- Martin Fowler — bài viết "Specification" pattern (martinfowler.com)
- Drools Documentation — phần "Decision Tables" và "DRL Rule Language"
- Easy Rules (GitHub, thư viện Java rule engine nhẹ) — lựa chọn trung gian giữa tự code và Drools nếu cần rule engine đơn giản hơn

---
> 🔗 Liên hệ: Mục này là nền tảng thiết kế cho `12-microservices` (rule engine tách biệt khỏi service core giúp Bounded Context rõ ràng hơn) và `13-ai-engineering` (kết hợp rule engine truyền thống với AI-based scoring trong fraud detection).
