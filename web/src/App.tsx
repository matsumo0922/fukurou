import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { lazy, Suspense, useState } from "react";
import { BrowserRouter, Navigate, Route, Routes } from "react-router";
import { AppShell } from "./ui/layout/AppShell";
import { ActivityPage } from "./pages/ActivityPage";
import { I18nProvider } from "./i18n/I18nProvider";
import { ConfigPage } from "./pages/ConfigPage";
import { ControlsPage } from "./pages/ControlsPage";
import { OverviewPage } from "./pages/OverviewPage";
import { SystemPage } from "./pages/SystemPage";

export default function App() {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            retry: false,
            refetchOnWindowFocus: false,
          },
        },
      }),
  );

  return (
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/" element={<Navigate to="/app/overview" replace />} />
            <Route path="/app" element={<AppShell />}>
              <Route index element={<Navigate to="overview" replace />} />
              <Route path="overview" element={<OverviewPage />} />
              <Route path="activity" element={<ActivityPage />} />
              <Route path="config" element={<ConfigPage />} />
              <Route path="controls" element={<ControlsPage />} />
              <Route path="evaluation" element={<Suspense fallback={<div>Evaluation console loading…</div>}><EvaluationPage /></Suspense>} />
              <Route path="system" element={<SystemPage />} />
            </Route>
            <Route path="*" element={<Navigate to="/app/overview" replace />} />
          </Routes>
        </BrowserRouter>
      </I18nProvider>
    </QueryClientProvider>
  );
}
const EvaluationPage = lazy(() => import("./pages/EvaluationPage").then((module) => ({ default: module.EvaluationPage })));
