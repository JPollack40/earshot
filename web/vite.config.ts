import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Served by GitHub Pages at https://jpollack40.github.io/earshot/
export default defineConfig({
  base: "/earshot/",
  plugins: [react()],
});
