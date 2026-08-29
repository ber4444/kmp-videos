import SwiftUI
import ComposeApp

@main
struct ICSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                // Draw under the status bar and home indicator. SwiftUI insets a
                // UIViewControllerRepresentable to the safe area by default, which
                // left white bands above and below the landing screen's background
                // photo. This matches Android's enableEdgeToEdge(); the shared
                // Compose UI owns its own insets (and its own keyboard handling).
                .ignoresSafeArea()
                // The Discord OAuth redirect returns on the `discord-<APP_ID>`
                // scheme registered in project.yml's CFBundleURLTypes. Forward
                // every URL — the shared code ignores the ones that are not an
                // authorization result.
                .onOpenURL { url in
                    MainViewControllerKt.handleDeepLink(url: url.absoluteString)
                }
        }
    }
}

struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.mainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
