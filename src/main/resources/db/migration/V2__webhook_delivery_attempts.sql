create table webhook_deliveries (
    id uuid primary key,
    integration_name varchar(255) not null,
    scope varchar(255) not null,
    status varchar(255) not null,
    payload_summary varchar(2000) not null,
    attempted_at timestamptz not null
);
create index idx_webhook_deliveries_attempted on webhook_deliveries(attempted_at desc);
