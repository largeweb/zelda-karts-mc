// Native side of the transport integration check; contains no game dependencies.
#include "../../ship/PlatformWindows.h"
#include "../../ship/Protocol.h"
#include <cstdlib>
#include <iostream>

int main(int argc, char** argv) {
    if (argc != 3) return 2;
    const size_t capacity = std::strtoull(argv[2], nullptr, 10);
    composite::windows::Mapping mapping;
    if (!mapping.openEnvironment(L"COMPOSITE_TEST_PATH", capacity)) return 73;
    if (std::string(argv[1]) == "try") return 0;
    composite::release(mapping.data(), composite::MAGIC);
    composite::release(mapping.data() + 4, composite::VERSION);
    composite::Minecraft value{};
    value.tick = 42;
    composite::write(mapping.data(), composite::MC, value);
    std::cout << "READY" << std::endl;
    std::cin.get();
    if (!composite::read(mapping.data(), composite::MC, value) || value.tick != 99) return 3;
    std::cout << "PASS" << std::endl;
    return 0;
}
