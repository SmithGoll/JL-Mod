//
// Created by woesss on 08.07.2023.
//

#ifndef MMAPI_EAS_PLAYER_H
#define MMAPI_EAS_PLAYER_H

#include "libsonivox/eas.h"
#include "eas_file.h"
#include "mmapi/PlayerListener.h"
#include "mmapi/BasePlayer.h"
#include "util/jbytearray.h"

namespace mmapi {
    namespace eas {
        class Player : public BasePlayer {
            static EAS_DLSLIB_HANDLE soundBank;

            const S_EAS_LIB_CONFIG *easConfig = EAS_Config();
            EAS_DATA_HANDLE easHandle;
            EAS_HANDLE media;
            EAS_HANDLE interactive;
            BaseFile *file;

        public:
            Player(EAS_DATA_HANDLE easHandle, BaseFile *file, EAS_HANDLE stream, const int64_t duration);
            Player(EAS_DATA_HANDLE easHandle);
            ~Player() override;

            void deallocate() override;
            void close() override;
            oboe::Result pause() override;
            oboe::Result prefetch() override;
            int32_t setDataSource(BaseFile *pFile);
            jint writeMIDI(util::JByteArrayPtr &data);

            oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream,
                                                  void *audioData,
                                                  int32_t numFrames)
                                                  override;

            static int32_t initSoundBank(const char *sound_bank);
            static int32_t createPlayer(const char *locator, Player **pPlayer);

        protected:
            oboe::Result createAudioStream() override;

        private:
            static int32_t openSource(EAS_DATA_HANDLE easHandle,
                                      BaseFile *pFile,
                                      EAS_HANDLE *outStream,
                                      int64_t *outDuration);
        }; // class Player
    } // namespace eas
} // namespace mmapi

#endif //MMAPI_EAS_PLAYER_H
