import { lazy, Suspense } from "react";
import { Route, Routes } from "react-router-dom";
import { AppProvider } from "./app/AppContext";
import { AdminLayout } from "./components/AdminLayout";
import { PublicLayout } from "./components/PublicLayout";
import { UnsavedChangesProvider } from "./components/UnsavedChangesGuard";
import { HomePage } from "./pages/public/HomePage";

const AdminAnalyticsPage = lazy(() =>
  import("./pages/admin/AdminAnalyticsPage").then((module) => ({
    default: module.AdminAnalyticsPage,
  })),
);
const AdminDashboardPage = lazy(() =>
  import("./pages/admin/AdminDashboardPage").then((module) => ({
    default: module.AdminDashboardPage,
  })),
);
const AdminDocsPage = lazy(() =>
  import("./pages/admin/AdminDocsPage").then((module) => ({ default: module.AdminDocsPage })),
);
const AdminLoginPage = lazy(() =>
  import("./pages/admin/AdminLoginPage").then((module) => ({ default: module.AdminLoginPage })),
);
const AdminManagedPagesPage = lazy(() =>
  import("./pages/admin/AdminManagedPagesPage").then((module) => ({
    default: module.AdminManagedPagesPage,
  })),
);
const AdminProductPage = lazy(() =>
  import("./pages/admin/AdminProductPage").then((module) => ({
    default: module.AdminProductPage,
  })),
);
const AdminProductPreviewPage = lazy(() =>
  import("./pages/admin/AdminProductPreviewPage").then((module) => ({
    default: module.AdminProductPreviewPage,
  })),
);
const AdminProductPreviewFramePage = lazy(() =>
  import("./pages/admin/AdminProductPreviewFramePage").then((module) => ({
    default: module.AdminProductPreviewFramePage,
  })),
);
const AdminMediaPage = lazy(() =>
  import("./pages/admin/AdminMediaPage").then((module) => ({ default: module.AdminMediaPage })),
);
const AdminReleasesPage = lazy(() =>
  import("./pages/admin/AdminReleasesPage").then((module) => ({
    default: module.AdminReleasesPage,
  })),
);
const AdminSitePage = lazy(() =>
  import("./pages/admin/AdminSitePage").then((module) => ({ default: module.AdminSitePage })),
);
const ChangelogPage = lazy(() =>
  import("./pages/public/ChangelogPage").then((module) => ({
    default: module.ChangelogPage,
  })),
);
const DocArticlePage = lazy(() =>
  import("./pages/public/DocArticlePage").then((module) => ({
    default: module.DocArticlePage,
  })),
);
const DocsPage = lazy(() =>
  import("./pages/public/DocsPage").then((module) => ({ default: module.DocsPage })),
);
const DownloadPage = lazy(() =>
  import("./pages/public/DownloadPage").then((module) => ({ default: module.DownloadPage })),
);
const ManagedPagePage = lazy(() =>
  import("./pages/public/ManagedPagePage").then((module) => ({
    default: module.ManagedPagePage,
  })),
);
const NotFoundPage = lazy(() =>
  import("./pages/public/NotFoundPage").then((module) => ({
    default: module.NotFoundPage,
  })),
);
const ProductPage = lazy(() =>
  import("./pages/public/ProductPage").then((module) => ({
    default: module.ProductPage,
  })),
);

function RouteLoader() {
  return <div className="route-loader" aria-busy="true">正在加载页面…</div>;
}

export function App() {
  return (
    <AppProvider>
      <UnsavedChangesProvider>
        <Suspense fallback={<RouteLoader />}>
          <Routes>
          <Route element={<PublicLayout />}>
            <Route index element={<HomePage />} />
            <Route path="product" element={<ProductPage />} />
            <Route path="download" element={<DownloadPage />} />
            <Route path="docs" element={<DocsPage />} />
            <Route path="docs/:slug" element={<DocArticlePage />} />
            <Route path="changelog" element={<ChangelogPage />} />
            <Route path="about" element={<ManagedPagePage slug="about" />} />
            <Route path="privacy" element={<ManagedPagePage slug="privacy" />} />
            <Route path="contact" element={<ManagedPagePage slug="contact" />} />
            <Route
              path="/admin/product/preview/frame"
              element={<AdminProductPreviewFramePage />}
            />
            <Route path="*" element={<NotFoundPage />} />
          </Route>
          <Route path="/admin/login" element={<AdminLoginPage />} />
          <Route path="/admin/product/preview" element={<AdminProductPreviewPage />} />
          <Route path="/admin" element={<AdminLayout />}>
            <Route index element={<AdminDashboardPage />} />
            <Route path="site" element={<AdminSitePage />} />
            <Route path="product" element={<AdminProductPage />} />
            <Route path="pages" element={<AdminManagedPagesPage />} />
            <Route path="media" element={<AdminMediaPage />} />
            <Route path="releases" element={<AdminReleasesPage />} />
            <Route path="docs" element={<AdminDocsPage />} />
            <Route path="analytics" element={<AdminAnalyticsPage />} />
          </Route>
          </Routes>
        </Suspense>
      </UnsavedChangesProvider>
    </AppProvider>
  );
}
