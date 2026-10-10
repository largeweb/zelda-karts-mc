#pragma once
#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <process.h>
#include <cstdint>
#include <string>
#define getpid _getpid

namespace composite::windows {
// Read paths through the wide API: Java's environment can contain Unicode paths.
inline std::wstring environmentPath(const wchar_t* name) {
    DWORD length = GetEnvironmentVariableW(name, nullptr, 0);
    if (!length) return {};
    std::wstring value(length, L'\0');
    DWORD copied = GetEnvironmentVariableW(name, value.data(), length);
    if (!copied || copied >= length) return {};
    value.resize(copied);
    return value;
}

class Mapping {
    HANDLE file = INVALID_HANDLE_VALUE, owner = INVALID_HANDLE_VALUE, mapping = nullptr;
    uint8_t* view = nullptr;
    bool regular(HANDLE handle) {
        BY_HANDLE_FILE_INFORMATION info{};
        return GetFileType(handle) == FILE_TYPE_DISK && GetFileInformationByHandle(handle, &info) &&
               !(info.dwFileAttributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT));
    }
    void close() {
        if (view) UnmapViewOfFile(view);
        if (mapping) CloseHandle(mapping);
        if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
        if (owner != INVALID_HANDLE_VALUE) CloseHandle(owner);
        view = nullptr; mapping = nullptr; file = owner = INVALID_HANDLE_VALUE;
    }
public:
    Mapping() = default;
    Mapping(const Mapping&) = delete;
    Mapping& operator=(const Mapping&) = delete;
    ~Mapping() { close(); }
    uint8_t* data() const { return view; }
    bool open(const std::wstring& path, size_t capacity) {
        close();
        if (path.empty() || !capacity) return false;
        // Lock a companion file, not the transport bytes: Java maps and writes those.
        // Keeping this handle open excludes a second engine even in this process.
        owner = CreateFileW((path + L".engine-lock").c_str(), GENERIC_READ | GENERIC_WRITE, 0, nullptr,
                            OPEN_ALWAYS, FILE_FLAG_OPEN_REPARSE_POINT, nullptr);
        if (owner == INVALID_HANDLE_VALUE || !regular(owner)) { close(); return false; }
        file = CreateFileW(path.c_str(), GENERIC_READ | GENERIC_WRITE,
                           FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                           OPEN_ALWAYS, FILE_FLAG_OPEN_REPARSE_POINT, nullptr);
        LARGE_INTEGER size{}, required{};
        required.QuadPart = static_cast<LONGLONG>(capacity);
        if (file == INVALID_HANDLE_VALUE || !regular(file) || !GetFileSizeEx(file, &size)) {
            close(); return false;
        }
        // Do not resize an existing Java mapping when it already has the right size.
        if (size.QuadPart != required.QuadPart &&
            (!SetFilePointerEx(file, required, nullptr, FILE_BEGIN) || !SetEndOfFile(file))) {
            close(); return false;
        }
        mapping = CreateFileMappingW(file, nullptr, PAGE_READWRITE, 0, 0, nullptr);
        if (mapping) view = static_cast<uint8_t*>(MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, capacity));
        if (!view) { close(); return false; }
        return true;
    }
    bool openEnvironment(const wchar_t* name, size_t capacity) { return open(environmentPath(name), capacity); }
};
} // namespace composite::windows
#endif
