import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Served by GitHub Pages at https://jpollack40.github.io/earshot/
export default defineConfig({
  base: "/earshot/",
  plugins: [react()],
  build: {
    rolldownOptions: {
      output: {
        // Phase 15: React and Supabase in their own files. Each stays cached in the browser across
        // Earshot updates (only the app's own file changes), and no single file is over 500 kB.
        codeSplitting: {
          groups: [
            { name: "react", test: /node_modules[\\/](react|react-dom|scheduler)[\\/]/ },
            { name: "supabase", test: /node_modules[\\/](@supabase|iceberg-js|tslib)[\\/]/ },
          ],
        },
      },
    },
  },
});
