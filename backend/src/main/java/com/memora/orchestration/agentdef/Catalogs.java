package com.memora.orchestration.agentdef;

import static com.memora.orchestration.agentdef.AgentTemplates.l;
import static com.memora.orchestration.agentdef.AgentTemplates.m;

import java.util.List;

/**
 * Keyword catalogs used by the software-delivery templates with the {@code catalog.match} tool. They are data,
 * copied into each installed agent's behavior, so a tenant can edit them per agent. A requirement that matches no
 * entry is reported as unmatched; nothing is invented for it.
 */
final class Catalogs {

    private Catalogs() {
    }

    static final List<Object> API = l(
            m("key", "auth", "title", "Authentication", "keywords", l("auth", "login", "log in", "sign in", "sign-in", "signup", "sign up",
                    "register", "registration", "account", "password", "session", "oauth", "sso"),
                    "produce", m("endpoints", l("POST /api/auth/register", "POST /api/auth/login", "POST /api/auth/logout", "GET /api/auth/me"),
                            "entities", l("User", "Session"),
                            "ddl", "CREATE TABLE users (id uuid PRIMARY KEY, email varchar(320) NOT NULL UNIQUE, password_hash varchar(100) NOT NULL, "
                                    + "created_at timestamptz NOT NULL DEFAULT now());\nCREATE TABLE sessions (id uuid PRIMARY KEY, "
                                    + "user_id uuid NOT NULL REFERENCES users(id), expires_at timestamptz NOT NULL);")),
            m("key", "catalog", "title", "Product catalogue", "keywords", l("product", "catalog", "catalogue", "inventory", "listing", "item"),
                    "produce", m("endpoints", l("GET /api/products", "GET /api/products/{id}", "POST /api/admin/products",
                                    "PUT /api/admin/products/{id}", "DELETE /api/admin/products/{id}"),
                            "entities", l("Product", "Category"),
                            "ddl", "CREATE TABLE products (id uuid PRIMARY KEY, name varchar(200) NOT NULL, description text, "
                                    + "price_cents integer NOT NULL CHECK (price_cents >= 0), stock integer NOT NULL DEFAULT 0, category varchar(100));")),
            m("key", "search", "title", "Search", "keywords", l("search", "filter", "find", "browse", "lookup"),
                    "produce", m("endpoints", l("GET /api/products?q={text}&category={category}&page={n}"),
                            "entities", l("Full-text index on products"),
                            "ddl", "CREATE INDEX products_search ON products USING gin (to_tsvector('english', name || ' ' || coalesce(description, '')));")),
            m("key", "cart", "title", "Shopping cart", "keywords", l("cart", "basket"),
                    "produce", m("endpoints", l("GET /api/cart", "POST /api/cart/items", "PATCH /api/cart/items/{id}", "DELETE /api/cart/items/{id}"),
                            "entities", l("Cart", "CartItem"),
                            "ddl", "CREATE TABLE cart_items (id uuid PRIMARY KEY, user_id uuid NOT NULL, product_id uuid NOT NULL REFERENCES products(id), "
                                    + "quantity integer NOT NULL CHECK (quantity > 0));")),
            m("key", "checkout", "title", "Checkout and payments", "keywords", l("checkout", "payment", "pay ", "billing", "invoice"),
                    "produce", m("endpoints", l("POST /api/checkout", "POST /api/payments/webhook"), "entities", l("Payment"),
                            "ddl", "CREATE TABLE payments (id uuid PRIMARY KEY, order_id uuid NOT NULL, provider_ref varchar(200), status varchar(20) NOT NULL);")),
            m("key", "orders", "title", "Orders", "keywords", l("orders", "order history", "place an order", "purchase", "my orders"),
                    "produce", m("endpoints", l("POST /api/orders", "GET /api/orders", "GET /api/orders/{id}"), "entities", l("Order", "OrderItem"),
                            "ddl", "CREATE TABLE orders (id uuid PRIMARY KEY, user_id uuid NOT NULL, status varchar(20) NOT NULL, total_cents integer NOT NULL, "
                                    + "created_at timestamptz NOT NULL DEFAULT now());\nCREATE TABLE order_items (id uuid PRIMARY KEY, "
                                    + "order_id uuid NOT NULL REFERENCES orders(id), product_id uuid NOT NULL, quantity integer NOT NULL);")),
            m("key", "notifications", "title", "Notifications", "keywords", l("email", "notification", "sms", "reminder", "alert"),
                    "produce", m("endpoints", l("GET /api/notifications/preferences", "PUT /api/notifications/preferences"),
                            "entities", l("NotificationPreference"),
                            "ddl", "CREATE TABLE notification_preferences (user_id uuid PRIMARY KEY, email boolean NOT NULL DEFAULT true, "
                                    + "sms boolean NOT NULL DEFAULT false);")),
            m("key", "reporting", "title", "Reporting", "keywords", l("analytics", "report", "dashboard", "metric", "kpi"),
                    "produce", m("endpoints", l("GET /api/admin/metrics"), "entities", l("Metric (view)"),
                            "ddl", "-- reporting reads existing tables; no new table")));

    static final List<Object> CROSS_CUTTING = l(
            m("key", "persistence", "title", "Persistence", "keywords", l("database", "persistence", "persist", "storage", "store data"),
                    "produce", m("approach", "All state lives in the relational database; schema changes are versioned migrations (schema.sql is the first).")),
            m("key", "testing", "title", "Automated testing", "keywords", l("test", "qa", "quality", "coverage"),
                    "produce", m("approach", "API tests for every endpoint and UI tests for every screen, one per acceptance criterion (test-plan.md).")),
            m("key", "responsive", "title", "Responsive, accessible UI", "keywords", l("responsive", "mobile", "accessib", "layout"),
                    "produce", m("approach", "Breakpoints at 640 and 1024 px, keyboard-accessible components, contrast 4.5:1 (design-spec.md).")),
            m("key", "performance", "title", "Performance", "keywords", l("performance", "fast", "latency", "scalab", "scale"),
                    "produce", m("approach", "Indexes on every query path and response-time budgets checked by tests.")),
            m("key", "security", "title", "Security and privacy", "keywords", l("security", "privacy", "gdpr", "encrypt"),
                    "produce", m("approach", "TLS in transit, hashed credentials, a least-privilege database role, audited admin actions.")),
            m("key", "deployment", "title", "Deployment", "keywords", l("deploy", "hosting", "release"),
                    "produce", m("approach", "A container image per release; production releases need an approval gate.")));

    static final List<Object> SCREENS = l(
            m("key", "auth", "title", "Authentication", "keywords", l("auth", "login", "log in", "sign in", "sign-in", "signup", "sign up",
                    "register", "registration", "account", "password", "session"),
                    "produce", m("screens", l("Sign in", "Register", "Account"), "components", l("LoginForm", "RegisterForm", "AccountMenu"),
                            "apiRequired", true)),
            m("key", "catalog", "title", "Product catalogue", "keywords", l("product", "catalog", "catalogue", "inventory", "listing", "item"),
                    "produce", m("screens", l("Product list", "Product detail"), "components", l("ProductCard", "ProductGrid", "PriceTag"),
                            "apiRequired", true)),
            m("key", "search", "title", "Search", "keywords", l("search", "filter", "find", "browse"),
                    "produce", m("screens", l("Search results"), "components", l("SearchBar", "FilterPanel", "Pagination"), "apiRequired", true)),
            m("key", "cart", "title", "Shopping cart", "keywords", l("cart", "basket"),
                    "produce", m("screens", l("Cart"), "components", l("CartLine", "CartSummary"), "apiRequired", true)),
            m("key", "checkout", "title", "Checkout and payments", "keywords", l("checkout", "payment", "pay ", "billing"),
                    "produce", m("screens", l("Checkout", "Payment result"), "components", l("AddressForm", "PaymentForm", "OrderSummary"),
                            "apiRequired", true)),
            m("key", "orders", "title", "Orders", "keywords", l("orders", "order history", "my orders", "purchase"),
                    "produce", m("screens", l("Order history", "Order detail"), "components", l("OrderTable", "OrderStatusBadge"),
                            "apiRequired", true)),
            m("key", "layout", "title", "Responsive layout", "keywords", l("responsive", "mobile", "design", "layout", "accessib", "ui"),
                    "produce", m("screens", l("All screens: responsive shell"), "components", l("AppShell", "NavBar", "Footer"),
                            "apiRequired", false)),
            m("key", "notifications", "title", "Notifications", "keywords", l("email", "notification", "reminder"),
                    "produce", m("screens", l("Notification settings"), "components", l("NotificationToggle"), "apiRequired", true)),
            m("key", "reporting", "title", "Reporting", "keywords", l("analytics", "report", "dashboard", "metric"),
                    "produce", m("screens", l("Admin dashboard"), "components", l("MetricCard", "Chart"), "apiRequired", true)));
}
