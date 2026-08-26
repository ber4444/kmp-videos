import SwiftUI
import ComposeApp

@main
struct ICSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                // The Discord OAuth redirect returns as an `icsquared://` deep
                // link. Forward every URL — the shared code ignores the ones that
                // are not an authorization result.
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
