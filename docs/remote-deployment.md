# Remote Deployment Guide

*AWS (primary), Oracle Cloud, and Alibaba Cloud options*
*Last updated: May 2026*

---

## Current Status

| Provider | Status | Cost |
|----------|--------|------|
| **AWS eu-west-1** | ✅ **ACTIVE** — t3.micro in Dublin, GHA auto-deploy on push to `main` | Free (12 months), then ~€15/mo |
| AWS us-west-2 | ❌ Terminated | — |
| Oracle | ⏸️ Not needed — AWS working | €0 (if wanted later) |
| Alibaba | ⏸️ Not needed — AWS working | ~€4/mo post-free-tier |

---

## AWS — eu-west-1 (Dublin) ✅ PRIMARY

*EC2 t3.micro, 12 months free, Dublin region (~5ms from Ireland), CV value for Irish market.*

### Prerequisites

- AWS account (free tier eligible)
- SSH key: `~/.ssh/market-signals.pem` (chmod 600)
- Telegram bot token; (optional) IG API credentials

### Step 1 — Create EC2 Instance

1. Console → **EC2** → **Instances** → **Launch Instances**

| Setting | Value |
|---------|-------|
| Name | `market-signals` |
| AMI | Ubuntu Server 24.04 LTS (HVM), SSD — Free tier eligible |
| Instance Type | **t3.micro** — Free tier eligible |
| Key Pair | Create new or select existing |
| Network Settings | Create security group (see Step 2) |
| Storage | **30 GB gp3** (change from default 8GB) |
| Advanced → User Data | (Optional) bootstrap script below |

**⚠️ SSH Security:** Console defaults SSH to `0.0.0.0/0` — change **Allow SSH traffic from** to **My IP** before launching.

**Bootstrap script** (optional — runs on first boot):
```bash
#!/bin/bash
apt update
apt install -y docker.io docker-compose
usermod -aG docker ubuntu
systemctl enable docker
```

2. **Launch Instance** → wait for "2/2 checks passed" (1–2 min)

### Step 2 — Security Group

1. **EC2 → Instances → market-signals → Security → Security groups → Edit inbound rules**

| Rule | Port | Source |
|------|------|--------|
| SSH | 22 | My IP |
| Custom TCP | 8080 | My IP |

> 🔒 Keep 8080 restricted to your IP unless you need external access (e.g. webhooks).

### Step 3 — Elastic IP (Static IP)

1. **EC2 → Network & Security → Elastic IPs → Allocate Elastic IP address**
2. Select the IP → **Actions → Associate Elastic IP address** → select your instance

Elastic IPs are free **only when attached to a running instance** — detach before stopping to avoid charges (~€0.005/hr).

### Step 4 — SSH & Docker Setup

```bash
ssh -i ~/.ssh/market-signals.pem ubuntu@YOUR_ELASTIC_IP

# If Docker not installed via bootstrap:
sudo apt update && sudo apt install -y docker.io docker-compose
sudo usermod -aG docker $USER && sudo systemctl enable docker && newgrp docker
```

### Step 5 — Deploy Application

```bash
# On EC2 — create directory
mkdir -p ~/apps/market-signals && cd ~/apps/market-signals

# From Mac — copy files (code + .env + DB backup)
scp -i ~/.ssh/market-signals.pem -r docker-compose.yml Dockerfile pom.xml src .env backup-*.sql \
  ubuntu@YOUR_ELASTIC_IP:/home/ubuntu/apps/market-signals/

# On EC2 — restore DB and start
docker-compose up -d postgres
sleep 5
docker exec -i market-signals-postgres psql -U postgres -c "CREATE DATABASE market_signals;"
docker exec -i market-signals-postgres psql -U postgres market_signals < backup-*.sql
docker-compose down && docker-compose up -d --build

# Enable IG instruments (IDs may vary — check /api/instruments first)
curl -X PATCH http://YOUR_ELASTIC_IP:8080/api/instruments/5/toggle  # DAX
curl -X PATCH http://YOUR_ELASTIC_IP:8080/api/instruments/6/toggle  # FTSE
curl -X PATCH http://YOUR_ELASTIC_IP:8080/api/instruments/7/toggle  # S&P 500
curl -X PATCH http://YOUR_ELASTIC_IP:8080/api/instruments/8/toggle  # Gold
```

**Required `.env` additions for remote deployment:**
```bash
SERVER_ADDRESS=0.0.0.0
```

**Verify:**
```bash
curl http://YOUR_ELASTIC_IP:8080/actuator/health
curl http://YOUR_ELASTIC_IP:8080/api/instruments/enabled
```

### Step 6 — Handle 1GB RAM (Swap + JVM caps)

t3.micro has 1 GB RAM — Java + PostgreSQL will OOM without **both** swap and JVM caps.

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h
```

**Current JVM caps** (set in committed `Dockerfile` ENTRYPOINT, do not weaken without profiling):
```dockerfile
ENTRYPOINT ["java",
  "-Xmx320m",
  "-XX:MaxMetaspaceSize=128m",
  "-XX:MaxDirectMemorySize=32m",
  "-XX:+UseSerialGC",
  "-jar", "app.jar"]
```
Docker hard limits (in `docker-compose.yml`): app container `memory: 450M`, Postgres `memory: 100M`. See `AGENTS.md` § Efficiency Guardrails.

### Step 7 — Maintenance

```bash
# View logs
docker-compose logs -f app

# Update application
git pull && docker-compose down && docker-compose up -d --build

# Check resource usage
free -h && df -h && docker stats

# Backup DB locally
docker exec market-signals-postgres pg_dump -U postgres market_signals > backup-$(date +%F).sql
scp backup-*.sql your-mac:~/Downloads/
```

### Step 8 — Terraform (Optional)

**`main.tf`:**
```hcl
terraform {
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }
}

provider "aws" {
  region = "eu-west-1"
}

resource "aws_security_group" "market_signals" {
  name_prefix = "market-signals"

  ingress { from_port = 22;   to_port = 22;   protocol = "tcp"; cidr_blocks = ["YOUR.IP/32"] }
  ingress { from_port = 8080; to_port = 8080; protocol = "tcp"; cidr_blocks = ["YOUR.IP/32"] }
  egress  { from_port = 0;    to_port = 0;    protocol = "-1";  cidr_blocks = ["0.0.0.0/0"] }
}

resource "aws_instance" "market_signals" {
  ami                    = "ami-0c1c30571d2dae5c9"  # Ubuntu 22.04 eu-west-1
  instance_type          = "t3.micro"
  key_name               = "your-key-pair-name"
  vpc_security_group_ids = [aws_security_group.market_signals.id]

  root_block_device { volume_size = 30 }

  user_data = <<-EOF
              #!/bin/bash
              apt update
              apt install -y docker.io docker-compose
              usermod -aG docker ubuntu
              systemctl enable docker
              EOF

  tags = { Name = "market-signals" }
}

resource "aws_eip" "market_signals" {
  instance = aws_instance.market_signals.id
  domain   = "vpc"
}

output "public_ip" {
  value = aws_eip.market_signals.public_ip
}
```

```bash
terraform init && terraform plan && terraform apply
ssh -i ~/.ssh/market-signals.pem ubuntu@$(terraform output -raw public_ip)
```

### Step 9 — CloudWatch Logs (Optional)

```bash
wget https://s3.amazonaws.com/amazoncloudwatch-agent/ubuntu/amd64/latest/amazon-cloudwatch-agent.deb
sudo dpkg -i amazon-cloudwatch-agent.deb

sudo tee /opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json << 'EOF'
{
  "logs": {
    "logs_collected": {
      "files": {
        "collect_list": [{
          "file_path": "/home/ubuntu/apps/market-signals/logs/*.log",
          "log_group_name": "market-signals",
          "log_stream_name": "{instance_id}"
        }]
      }
    }
  }
}
EOF

sudo systemctl enable amazon-cloudwatch-agent && sudo systemctl start amazon-cloudwatch-agent
```

View in Console → **CloudWatch → Log groups → market-signals**.

### Step 10 — Free SSL with Let's Encrypt (Optional)

```bash
sudo apt install -y nginx certbot python3-certbot-nginx

sudo tee /etc/nginx/sites-available/market-signals << 'EOF'
server {
    listen 80;
    server_name signals.yourdomain.com;
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
EOF

sudo ln -s /etc/nginx/sites-available/market-signals /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl restart nginx
sudo certbot --nginx -d signals.yourdomain.com
```

Then update Security Group: remove 8080, add 443.

### Security Checklist

- [ ] **Port 22 and 8080 restricted to your IP (not 0.0.0.0/0)**
- [ ] Elastic IP attached (stable endpoint)
- [ ] Swap file configured (prevents OOM)
- [ ] `.env` permissions: `chmod 600 .env`
- [ ] `.env` in `.gitignore` — no secrets committed
- [ ] IG demo account only (if enabled)
- [ ] `TRADING_AUTO_EXECUTION_ENABLED=false`

### Troubleshooting

**Can't reach port 8080:**
```bash
# Check security group allows your IP
# Check .env has SERVER_ADDRESS=0.0.0.0
docker exec market-signals-service netstat -tlnp
```

**Out of memory:**
```bash
free -h && swapon -s
dmesg | grep -i "killed process"
```

**Docker permission denied:**
```bash
exit && ssh ...  # Re-login to pick up docker group
# Or: newgrp docker
```

**SSH Permission denied (publickey):**
```bash
mv ~/Downloads/market-signals.pem ~/.ssh/
chmod 600 ~/.ssh/market-signals.pem
ssh -i ~/.ssh/market-signals.pem ubuntu@YOUR_ELASTIC_IP
```

---

## Oracle Cloud (Truly Free Forever)

**€0 forever** — 4 OCPUs + 24GB RAM (ARM) or 2 VMs (AMD). Signup is flaky but worth it for zero ongoing cost.

### 1. Sign Up
- https://cloud.oracle.com/free
- Pick region: **eu-frankfurt-1** (closest to Ireland)
- Credit card for verification (not charged)
- If stuck at "That name didn't work" — wait 10 mins, refresh, try incognito

### 2. Create VM
- Compute → Instances → Create
- Image: **Ubuntu 22.04**
- Shape: **VM.Standard.A1.Flex** (ARM, always free)
- OCPU: 1, Memory: 6GB
- Boot volume: 50GB
- Download SSH key

### 3. Open Ports
- Networking → VCN → Subnet → Default Security List → Add Ingress:
  - Port 22 (SSH) from your IP
  - Port 8080 (app) from your IP

### 4. Connect & Setup
```bash
chmod 600 ~/Downloads/ssh-key-*.key
ssh -i ~/Downloads/ssh-key-*.key ubuntu@ORACLE_IP

sudo apt update && sudo apt install -y docker.io docker-compose
sudo usermod -aG docker $USER && newgrp docker
mkdir -p ~/apps/market-signals && cd ~/apps/market-signals
```

### 5. Deploy
```bash
# From Mac — copy files
scp -i ~/Downloads/ssh-key-*.key \
  docker-compose.yml Dockerfile pom.xml src .env \
  ubuntu@ORACLE_IP:/home/ubuntu/apps/market-signals/

# On Oracle VM — start
ssh -i ~/Downloads/ssh-key-*.key ubuntu@ORACLE_IP
cd ~/apps/market-signals
docker-compose up -d --build
```

### 6. DB Migration (if moving from MacBook)
```bash
# On MacBook first
make up && sleep 10
docker exec market-signals-postgres pg_dump -U postgres market_signals > backup.sql
make down

# Copy to Oracle and restore
scp -i ~/Downloads/ssh-key-*.key backup.sql ubuntu@ORACLE_IP:/home/ubuntu/apps/market-signals/
ssh -i ~/Downloads/ssh-key-*.key ubuntu@ORACLE_IP
cd ~/apps/market-signals
docker-compose up -d postgres && sleep 5
docker exec -i market-signals-postgres psql -U postgres -c "CREATE DATABASE market_signals;"
docker exec -i market-signals-postgres psql -U postgres market_signals < backup.sql
docker-compose down && docker-compose up -d --build
```

**Cost: €0 forever.**

---

## Alibaba Cloud (Simpler Alternative)

€0 free tier (1 year), then ~€4-5/month. Easier signup than Oracle.

### 1. Sign Up
- https://www.alibabacloud.com
- Email + phone verification + payment method

### 2. Create Instance
- Console → Elastic Compute Service → Create Instance
- Region: **Frankfurt (eu-central-1)**
- Instance: **ecs.t6-c1m1.large** (free tier eligible)
- Image: Ubuntu 22.04, Storage: 40GB

### 3. Security Group
- Add rules: Port 22 (SSH), Port 8080 (app) from your IP

### 4. Deploy
Same steps as Oracle — SSH in, install Docker, copy files, start services.

**Cost:** Free 1 year, then ~€4/month.

---

## CI/CD with GitHub Actions

Auto-deploy on every push to `main`:

**`.github/workflows/deploy.yml`:**
```yaml
name: Deploy to EC2

on:
  push:
    branches: [main, master]

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Deploy to EC2
        uses: appleboy/ssh-action@v1.0.0
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ubuntu
          key: ${{ secrets.EC2_SSH_KEY }}
          script: |
            cd ~/apps/market-signals
            git pull origin main
            docker-compose down
            docker-compose up -d --build
            docker-compose ps
```

**Setup:** Add secrets in Repository → Settings → Secrets → Actions:
- `EC2_HOST` = your Elastic IP
- `EC2_SSH_KEY` = contents of `~/.ssh/market-signals.pem`

Works with any provider — update secrets accordingly.

---

## Database Backup/Restore

**Backup (any running instance):**
```bash
docker exec market-signals-postgres pg_dump -U postgres market_signals > backup-$(date +%F).sql
```

**Restore:**
```bash
docker exec -i market-signals-postgres psql -U postgres market_signals < backup.sql
```

---

## Historical Data & P&L Analysis

| Feature | Endpoint | Description |
|---------|----------|-------------|
| **P&L Report** | `GET /api/positions/pnl-summary` | Live JSON with open/closed positions, win rate, expectancy |
| **P&L CSV** | `GET /api/positions/pnl-report/csv` | Same data, CSV format |
| **Signal Gaps** | `GET /api/positions/signal-gaps` | Signals that fired but didn't open a position (cooldown / quiet hours / duplicate-open) |
| **Daily Report** | `./reports/pnl-report.md` | Auto-written at 06:00 UTC by `PositionReportService` |
| **Signal History** | `signal_logs` table | Every signal stored with timestamp, price, RSI values (90-day retention) |
| **Position History** | `position_outcomes` table | Open + closed positions with TP/SL/PNL (90-day retention) |
| **Candle History** | `candle_history` table | 60-day rolling window per `symbol:timeframe` |
| **Backtest Analysis** | See `docs/archived/backtest-report.md` | Apr 2026 signal quality backtest (findings actioned) |

```bash
EC2_IP=$(your-elastic-ip)
curl http://$EC2_IP:8080/api/positions/pnl-summary

ssh -i ~/.ssh/market-signals.pem ubuntu@$EC2_IP
cat ~/apps/market-signals/reports/pnl-report.md
```

---

*See `docs/api.md` for full endpoint reference, `docs/schema.md` for DB tables, and `AGENTS.md` for deploy + runtime guardrails.*
