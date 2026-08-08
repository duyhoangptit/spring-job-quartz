# 13. AI Engineering — Tích hợp LLM vào hệ thống Banking

## Mục tiêu
Hiểu cách tích hợp AI/LLM vào hệ thống production một cách có trách nhiệm — đặc biệt quan trọng trong domain banking nơi sai sót AI có hệ quả tài chính/pháp lý.

## Kiến thức cốt lõi
- **LLM API Integration**: gọi model qua API (Claude/OpenAI), streaming response, xử lý timeout/retry cho use case production (không phải demo).
- **RAG (Retrieval-Augmented Generation)**: kết hợp vector search (pgvector trên PostgreSQL bạn đã có, hoặc dedicated vector DB) với LLM để trả lời dựa trên tài liệu nội bộ (chính sách, quy định) thay vì "bịa" (hallucination).
- **Prompt Engineering cho domain cụ thể**: structured output (JSON mode/function calling) để tích hợp an toàn vào pipeline có kiểu dữ liệu rõ ràng, không parse text tự do.
- **AI trong Fraud Detection**: kết hợp rule-based system (nhanh, giải thích được) với ML/LLM-based anomaly detection (linh hoạt hơn) — hiểu trade-off giữa **explainability** (bắt buộc trong banking để giải trình quyết định từ chối giao dịch) và độ chính xác.
- **Human-in-the-loop**: AI đề xuất, con người phê duyệt cho quyết định có rủi ro cao (VD: khoá tài khoản, từ chối giao dịch lớn) — không để AI tự động quyết định 100% trong domain nhạy cảm.
- **Guardrails & Evaluation**: đánh giá output AI có nhất quán, an toàn không trước khi đưa vào production; content filtering cho các luồng có tương tác với khách hàng (chatbot).
- **AI-assisted Development** (dùng chính Claude Code): code review tự động theo checklist bảo mật, sinh test case cho edge case tài chính.

## Điểm cần chú ý
- **Không dùng LLM để tự quyết định duyệt/từ chối giao dịch tài chính mà không có cơ chế giải trình** — banking cần audit được "vì sao" một quyết định được đưa ra; LLM thuần "black-box" khó đáp ứng yêu cầu compliance.
- Hallucination trong RAG khi tài liệu nguồn không đủ hoặc retrieval kém — luôn có bước validate/citation rõ ràng, không tin tưởng output LLM 100%.
- Đưa dữ liệu khách hàng nhạy cảm (số tài khoản, số dư) vào prompt gửi tới LLM API bên thứ 3 mà không kiểm tra chính sách data retention/privacy của nhà cung cấp — rủi ro compliance nghiêm trọng.
- Dùng AI cho fraud detection nhưng không có feedback loop (theo dõi false positive/negative thực tế) → model "lệch" dần theo thời gian mà không ai biết.

## Ứng dụng vào Banking High-Concurrency
- **Fraud Detection Service**: kết hợp rule engine nhanh (chặn tức thì các pattern rõ ràng: vượt hạn mức, địa lý bất thường) chạy đồng bộ trong transaction path, và **AI-based anomaly scoring** chạy bất đồng bộ (qua Kafka event) để gắn cờ giao dịch nghi ngờ cho đội vận hành xem xét — không làm chậm giao dịch chính.
- **RAG-based Compliance Assistant**: hệ thống nội bộ giúp nhân viên tra cứu quy định/chính sách nhanh, trích dẫn rõ nguồn tài liệu, giảm thời gian xử lý support ticket.
- **AI-assisted Code Review**: dùng Claude Code trong pipeline CI để review các PR động vào `transaction-service` theo checklist bảo mật/concurrency đã xây dựng xuyên suốt lộ trình này — tăng tốc mà vẫn giữ chất lượng.

## Bài tập thực hành
Xây dựng module `fraud-detection-service`: nhận event `TransactionCompleted` từ Kafka, chạy qua rule engine đơn giản + gọi LLM API để phân tích pattern bất thường dựa trên lịch sử giao dịch gần nhất, output ra risk score kèm giải thích (explainability), ghi vào bảng riêng để đội vận hành review.

## Tài nguyên
- Anthropic Documentation (docs.claude.com) — phần Tool Use/Function Calling, Prompt Engineering
- "Designing Machine Learning Systems" — Chip Huyen (phần Human-in-the-loop, Monitoring)
