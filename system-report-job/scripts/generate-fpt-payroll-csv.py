#!/usr/bin/env python3
"""Sinh file CSV lương giả lập cho sample BANK_SALARY_PAYROLL (xem
docs/bank-salary-sample/bank-salary-sample.md và
docs/superpowers/specs/2026-08-24-bank-salary-payroll-design.md).

Usage:
    python3 scripts/generate-fpt-payroll-csv.py \
        --count 30000 \
        --out docs/bank-salary-sample/sample-data/FPT_PAYROLL_2026-09-21.csv \
        --invalid-rate 0.01
"""
import argparse
import csv
import os
import random


def main():
    parser = argparse.ArgumentParser(description="Sinh CSV lương nhân viên FPT Software")
    parser.add_argument("--count", type=int, default=30000, help="Số nhân viên (mặc định 30000)")
    parser.add_argument("--out", required=True, help="Đường dẫn file CSV đầu ra")
    parser.add_argument(
        "--invalid-rate",
        type=float,
        default=0.01,
        help="Tỉ lệ dòng cố tình lỗi để test skip (mặc định 0.01 = 1%%)",
    )
    parser.add_argument("--seed", type=int, default=42, help="Seed để sinh dữ liệu lặp lại được")
    args = parser.parse_args()

    random.seed(args.seed)
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)

    invalid_count = 0
    with open(args.out, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["employeeId", "accountNumber", "fullName", "salaryAmount"])

        for i in range(1, args.count + 1):
            employee_id = f"FPT{i:06d}"
            full_name = f"Nhan Vien {i:06d}"
            salary = round(random.uniform(8_000_000, 60_000_000))
            account_number = f"{9_000_000_000_000 + i:013d}"

            if random.random() < args.invalid_rate:
                invalid_count += 1
                if random.random() < 0.5:
                    # Số tài khoản sai định dạng (không phải toàn số) - PayrollValidationProcessor sẽ skip.
                    account_number = f"BAD-{i}"
                else:
                    # Lương không hợp lệ - PayrollValidationProcessor sẽ skip.
                    salary = 0

            writer.writerow([employee_id, account_number, full_name, salary])

    print(f"Đã sinh {args.count} dòng ({invalid_count} dòng cố tình lỗi) vào {args.out}")


if __name__ == "__main__":
    main()
