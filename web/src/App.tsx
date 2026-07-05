import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { BrowserRouter, Navigate, Route, Routes } from "react-router";
import { AppShell } from "./ui/layout/AppShell";
import { ActivityPage } from "./pages/ActivityPage";
import { EvaluationPage } from "./pages/EvaluationPage";
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
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<Navigate to="/app/overview" replace />} />
          <Route path="/app" element={<AppShell />}>
            <Route index element={<Navigate to="overview" replace />} />
            <Route path="overview" element={<OverviewPage />} />
            <Route path="activity" element={<ActivityPage />} />
            <Route path="evaluation" element={<EvaluationPage />} />
            <Route path="system" element={<SystemPage />} />
          </Route>
          <Route path="*" element={<Navigate to="/app/overview" replace />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
