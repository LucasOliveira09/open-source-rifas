# Rifas Iracema

Aplicação web para organizar e comprar rifas da escola Iracema.

## Estrutura

- `frontend/`: aplicação Angular.
- `backend/`: API Java com Spring Boot.

Versões iniciais: Angular 21, Spring Boot 4.1.1 e Java 21.

## Requisitos

- Node.js 24 e npm para o frontend.
- Java 21 ou superior para o backend.

## Executar localmente

Inicie o PostgreSQL local:

```powershell
docker compose up -d
```

No terminal 1, inicie a API:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

No terminal 2, inicie a aplicação web:

```powershell
cd frontend
npm start
```

O Angular ficará disponível em `http://localhost:4200`. A API Spring Boot usa `http://localhost:8080`.

O banco é preparado automaticamente pelo Flyway quando a API iniciar. A primeira migração cria a rifa e os números de 1 a 100. O preço começa sem configuração, então o checkout permanece desabilitado até a escola definir o valor. Os prêmios também serão exibidos assim que forem cadastrados.

Para habilitar o Pix, defina no terminal do backend as credenciais de teste do Mercado Pago antes de iniciar a API:

```powershell
$env:MERCADO_PAGO_ACCESS_TOKEN = "<access-token-de-teste>"
$env:MERCADO_PAGO_WEBHOOK_SECRET = "<segredo-do-webhook>"
```

O código Pix vence após 24 horas por padrão. A duração pode ser ajustada pela variável `PIX_EXPIRATION` (formato ISO 8601, de 30 minutos a 30 dias). O endpoint do Webhook é `POST /api/webhooks/mercadopago`; para receber notificações externas em desenvolvimento, a URL precisa estar publicamente acessível por HTTPS.

## Escopo em definição

O fluxo e as decisões pendentes estão em [docs/especificacao.md](docs/especificacao.md). A tela pública, as consultas da rifa, o formulário de compra e a integração Pix estão implementados. O checkout permanece desabilitado até que o preço seja definido e as credenciais do Mercado Pago sejam configuradas. Os prêmios ainda precisam ser cadastrados; não há painel administrativo nesta primeira etapa.
