import React, { Suspense } from "react";
import { ThemeProvider } from "styled-components";
import { IntlProvider } from "react-intl";
import { CacheProvider } from "rest-hooks";
import { QueryClient, QueryClientProvider } from "react-query";

import en from "locales/en.json";
import cloudLocales from "./locales/en.json";
import GlobalStyle from "global-styles";
import { theme } from "./theme";

import "packages/cloud/config/firebase";

import { Routing } from "./routes";
import LoadingPage from "components/LoadingPage";
import ApiErrorBoundary from "components/ApiErrorBoundary";
import NotificationServiceProvider from "hooks/services/Notification";
import { AnalyticsInitializer } from "views/common/AnalyticsInitializer";
import { FeatureService } from "hooks/services/Feature";
import { AuthenticationProvider } from "./services/auth/AuthService";
import { useCustomerIdProvider } from "./services";

const queryClient = new QueryClient();

const messages = Object.assign({}, en, cloudLocales);

const App: React.FC = () => {
  return (
    <React.StrictMode>
      <ThemeProvider theme={theme}>
        <GlobalStyle />
        <IntlProvider locale="en" messages={messages}>
          <QueryClientProvider client={queryClient}>
            <CacheProvider>
              <Suspense fallback={<LoadingPage />}>
                <ApiErrorBoundary>
                  <FeatureService>
                    <NotificationServiceProvider>
                      <AuthenticationProvider>
                        <AnalyticsInitializer
                          customerIdProvider={useCustomerIdProvider}
                        >
                          <Routing />
                        </AnalyticsInitializer>
                      </AuthenticationProvider>
                    </NotificationServiceProvider>
                  </FeatureService>
                </ApiErrorBoundary>
              </Suspense>
            </CacheProvider>
          </QueryClientProvider>
        </IntlProvider>
      </ThemeProvider>
    </React.StrictMode>
  );
};

export default App;
